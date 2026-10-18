# Ministry export packs

A monthly pack per scheme for the Ministry: an Excel workbook and a short PDF summary, built by the
queue worker on the 1st (India time) for the month before, kept privately in the media bucket and
announced to subscribed recipients by e-mail. A Ministry Admin, an admin or the master admin can
also generate any past month, or the current month to date, on `/ministry-exports`.

**It ships OFF.** `MINISTRY_EXPORTS_ENABLED` defaults to `false`; while it is off every route answers
404, the scheduler does nothing and neither client draws an entry point (§6).

| | |
|---|---|
| Backend | `backend/app/services/ministry_exports.py` (schedule, queue, build, digest), `ministry_pack_sheets.py` (the sheets, pure), `ministry_pack_pdf.py` (the PDF), `backend/app/api/routes/ministry_exports.py`, `backend/app/schemas/ministry_exports.py`, the third block of `backend/app/worker.py` |
| Web | `frontend/app/(protected)/ministry-exports/page.tsx`, `frontend/lib/ministryExports.ts`; entry points in `components/DynamicIslandNav.tsx`, `app/(protected)/admin/page.tsx` and the ministry dashboard's header |
| Android | none — ministry authoring is web-only ([DECISION-ministry-surfaces-web-only.md](DECISION-ministry-surfaces-web-only.md)) |
| Schema | `20261018120000_ministry_export_packs`: `MinistryExportRecipient`, `MinistryExportPack`, `MinistryExportDownload` ([DATA_MODEL.md](DATA_MODEL.md) §6) |
| Infra | `infra/terraform/main.tf`: the `AppMinistryExports` IAM statement and the `expire-ministry-exports` lifecycle rule; `variables.tf`: `ministry_export_days` |
| Tests | `backend/tests/test_ministry_exports_schedule.py`, `_queue.py`, `_routes.py`, `_mail.py`, `test_ministry_pack_sheets.py`, `test_ministry_pack_pdf.py`; `frontend/e2e/ministry-exports-unit.spec.ts` |

---

## 1. What a pack is

One pack per **period** (`"YYYY-MM"`, India time) and **scheme**. A scheme is a `WORKSHOP_KIND`
token — the six kinds the registry fixes — or `UNSTATED` for workshops whose kind was never stated
("Scheme not yet stated"), so that no workshop silently drops out of every pack. Soft-deleted
workshops are excluded.

**The workbook** opens on a **Summary** sheet: period, scheme, the scope sentence ("Every workshop
under Skill Upgradation on the platform"), when the snapshot was taken, the revision, standing counts
(workshops, newly registered, ongoing, completed, artisans, sanctioned amount), what happened during
the period (registered, last handed in, approved, handed on — from real timestamps only), the annual
plan for the financial year the period falls in, a by-state table, the data-protection note and,
when the cap was hit, a truncation note. Then one sheet per register: **Workshops** (the columns of
the ministry dashboard's CSV plus scheme name, implementing agency, sponsor, sanction and hand-on),
**Sanction orders**, **Annual plan**, **Inspections** (notes filed and sent back, per workshop) and
**Report hand-ons** (file name, format, generated date, checksum — never a storage key).

**The PDF** is one or two pages: title, period, scheme, a month-to-date banner when the month is not
over, the headline figures, the top ten states and the data-protection footer. It names nobody.

**A snapshot, not history.** The platform cannot be reconstructed as of a past date, so both files
say when the snapshot was taken; regenerating an old month gives today's values under a new revision.

## 2. What is never in a pack

- **No stage answers.** A Ministry Admin may read design-workshop stage data on screen but may not take
  it out of the product (`deps.DESIGN_WORKSHOP_DATA_EXPORT_ROLES` is Admin and Master Admin only), and
  one pack goes to every recipient. So the pack carries the registers the Ministry already downloads
  and nothing from inside a workshop — no questionnaire answers, transcripts, media, URLs or AI layers.
- **No contact details.** No phone or e-mail column; a sanction order's designer e-mail is left out.
- **No full identity numbers.** `scrub_sheet` runs over every sheet: phone and e-mail columns are
  dropped, Aadhaar, Pehchan and artisan-card columns keep their last four digits (`XXXX XXXX 1234`),
  any twelve-digit run in free text is masked the same way, and any value that is a URL is blanked.
  The tests assert no twelve-digit run, no `http` and no `X-Amz` anywhere in a pack.

## 3. Schedule, queue and idempotency

The queue worker (`fieldrepo-queue`, the same unit that drains media and the e-mail outbox) calls
`ministry_exports.maybe_run` on every pass:

1. Every ten minutes: recover a pack stuck in BUILDING for over 30 minutes (back to QUEUED, or FAILED
   once its attempts are spent), and — at or after `MINISTRY_EXPORTS_RUN_HOUR_IST` on the 1st, and on
   any later day of the month if the worker was down — queue one SCHEDULED pack per scheme for the
   month before.
2. Every pass: claim **at most one** due pack (a compare-and-set on `status`) and build it — rows
   capped at `MINISTRY_EXPORTS_MAX_WORKSHOPS`, rendering off the event loop. The box is small and a
   build holds up the media queue, so one per pass is the rule.
3. Once every pack of the owed month has finished, send the digest (§5).

**Idempotent per month.** `MinistryExportPack` is unique on `(period, scheme)`. The scheduler inserts
what is missing and treats a unique violation as "already there"; regenerating bumps `revision` on the
same row, and storage keys carry the revision, so an earlier file is never overwritten. A pack built
during the month (month to date) is rebuilt once when the month is over. A failed build retries after
5, 20 and 60 minutes, then stays FAILED with a short reason (never row data). A scheme with no
workshops is EMPTY and has no files. No past months are backfilled on first deploy; generate them.

## 4. Storage and downloads

`exports/ministry/{period}/{scheme}/r{revision}/ministry-pack-{scheme-slug}-{period}.xlsx|.pdf`.

Not under `media/`, which is public-read, and not under `backups/`, where the API key may only put.
Terraform gives the key Put and Get on `exports/*` (no delete) and expires the prefix after
`ministry_export_days` (400 days, 13 months and a little). The row outlives its files; a download
after that answers 410 and the page says to generate it again.

A download is a presigned link minted per click — `POST /api/ministry-exports/packs/{id}/links`,
`MINISTRY_EXPORTS_LINK_SECONDS` (300) long — and every one is logged in `MinistryExportDownload`
with who, which file and which revision.

## 5. The e-mail

`MINISTRY_PACKS_READY`: one message per recipient per period — a digest of their subscribed schemes
that have workshops — sent once all of that period's scheduled packs have finished. Subject
"Ministry packs for September 2026 are ready". **No attachment and no presigned link**: the button
opens `/ministry-exports?period=YYYY-MM`, which needs a signed-in account, so a forwarded e-mail
exposes nothing. Duplicates are prevented by outbox topic `ministry-packs:{period}`. Generating on
demand **never** sends mail. There is no preference opt-out: subscribing is the opt-in, and the page
is where one unsubscribes.

## 6. Who may do what

`/ministry-exports` and every `/api/ministry-exports` route admit `OVERSIGHT_ASSIGNER_ROLES` —
Ministry Admin, Admin, Master Admin — and refuse everybody else with "For Ministry Admins, admins and
the master admin." A pack is built at whole-estate scope, so the two director tiers, who read only
the workshops they oversee, are out. Permissions are checked three times: when a recipient is added
(role, address, platform access), again when the mail is sent (a demoted or suspended recipient gets
nothing and is shown on the page with the reason), and when a link is minted.

The web learns whether the feature is on from `features.ministryExports` on the session payload (the
same key `GET /api/features` answers); the nav entry, the admin hub tile and the ministry dashboard's
"Monthly packs" link all hide while it is off.

## 7. Turning it on

1. `terraform apply` in `infra/terraform` (adds the IAM statement and the lifecycle rule).
2. Set `MINISTRY_EXPORTS_ENABLED=true` in the `BACKEND_ENV` secret and the box `.env`
   ([ENVIRONMENT.md](ENVIRONMENT.md) "Ministry export packs"); the next deploy restarts both units.
3. On `/ministry-exports`, add the recipients. The first scheduled run is the next 1st.

Mail needs `MAIL_FROM_ADDRESS` as for every other message; without it the packs are still built.

## 8. How this document is kept true

| Claim | Kept true by |
|---|---|
| Every route is 404 while the switch is off | `test_ministry_exports_routes.py::test_every_route_is_a_404_while_the_switch_is_off` |
| The three tiers, and one refusal on both surfaces | `test_ministry_exports_routes.py` (role matrix), `frontend/e2e/ministry-exports-unit.spec.ts`, `test_role_ladder_parity.py` |
| One pack per month and scheme; a rerun changes nothing | `test_ministry_exports_schedule.py` |
| One pack per pass, stale builds recovered, no READY row without files | `test_ministry_exports_queue.py` |
| No contact details, links, signatures or twelve-digit runs in a pack | `test_ministry_pack_sheets.py` |
| The PDF is one or two pages and says "month to date" when it is | `test_ministry_pack_pdf.py` |
| One digest per recipient per month, none on demand, no storage link | `test_ministry_exports_mail.py`, `test_mailer.py` |
| The variables are documented | [ENVIRONMENT.md](ENVIRONMENT.md)'s set-difference command |

**Review triggers:** `backend/app/services/ministry_exports.py`, `ministry_pack_sheets.py`,
`ministry_pack_pdf.py`, `OVERSIGHT_ASSIGNER_ROLES`, the `WORKSHOP_KIND` vocabulary, and the
`exports/` statement or lifecycle rule in `infra/terraform/main.tf`.

<!-- GENERATED FILE — do not edit by hand.
     Regenerate with:  node docs/tools/check-docs.mjs --write
     Every count in this documentation set lives here and nowhere else, so that a migration or a new
     route makes exactly one file wrong and a scripted run makes it right again. -->

# Repository facts (generated)

Counts derived from the working tree by `docs/tools/check-docs.mjs`. **Do not restate these numbers
in prose** — link here instead. If a document quotes a count, that count is already rotting.

These figures describe the **working tree**, which is not the same thing as production. The deployed
API lags the tree by however many commits have not been deployed; see
[the deployed-versus-tree note](#deployed-versus-tree).

## Data model

| | Count |
|---|---|
| Prisma models | **111** |
| Prisma enums | **42** |
| `@@index` declarations | 301 |
| `@@unique` declarations | 43 |

Models: `User`, `AssignedTask`, `Feedback`, `FeedbackReport`, `UserPreference`, `AppRelease`, `CraftCategory`, `CraftSubCategory`, `Craft`, `CraftCode`, `CraftSpelling`, `CrossPortalArtisanLink`, `ArtisanCard`, `ArtisanCardEvent`, `CrossPortalAccessLog`, `Location`, `Artisan`, `Workshop`, `WorkshopArtisan`, `WorkshopCraft`, `ProductDocumentation`, `ToolDocumentation`, `ToolArtisan`, `ToolCraft`, `MediaFile`, `TranscriptInsight`, `MediaProcessingJob`, `QuestionnaireSection`, `QuestionnaireSectionStatus`, `QuestionnaireQuestion`, `QuestionnaireInterview`, `QuestionnaireInterviewArtisan`, `QuestionnaireResponse`, `QuestionnaireAnswerDraft`, `Questionnaire`, `QuestionnaireFormSection`, `QuestionnaireFormQuestion`, `QuestionnaireFormEntry`, `QuestionnaireFormAnswer`, `Process`, `ProcessStep`, `ReviewLog`, `AppSetting`, `WorkshopAssignment`, `ManagedSecret`, `UserAiCredential`, `SecretTestResult`, `DataAccessGrant`, `DataAccessScopeItem`, `EntryComment`, `RecordRevision`, `DesignWorkshop`, `DesignWorkshopViewer`, `DesignWorkshopInspector`, `DesignWorkshopOversight`, `DwArtisanImport`, `DesignWorkshopAccessRequest`, `RecordAccessToken`, `RecordAccessTokenRedemption`, `DesignWorkshopProvisionalMember`, `DwStageEntry`, `DwCustomSection`, `DwCustomField`, `DwReportExport`, `DwAiLayer`, `DwAiLayerDecision`, `Consent`, `DwWorkshopConsentDecision`, `DwInspectionFeedback`, `DwDictationDailyUsage`, `DwAiVerbDailyUsage`, `DwReviewRating`, `DesignerRoster`, `DesignerProfile`, `SanctionOrder`, `PasswordResetToken`, `PasswordResetRequest`, `TwoFactorChallenge`, `AccessRoster`, `UsageEvent`, `UsageConsentDecision`, `AnnualPlanEntry`, `SanctionOrderDesigner`, `SanctionOrderImport`, `WorkshopTypeOption`, `RegionalDirectorState`, `EmailMessage`, `DwReportDelivery`, `IdempotencyKey`, `AadhaarOtpThrottle`, `DwInspectionClearance`, `DwRoundSnapshot`, `Notification`, `MinistryDataExport`, `AadhaarLogArchive`, `AadhaarLegalHold`, `WeeklyDigest`, `SearchDocument`, `SearchIndexQueue`, `ArtisanDuplicateCandidate`, `ArtisanPeerScan`, `ArtisanMerge`, `GeoAlias`, `MinistryExportRecipient`, `MinistryExportPack`, `MinistryExportDownload`, `SttProviderState`, `SttUsage`, `SttBudget`, `TranscriptionChunk`, `TranscriptCache`.

Enums: `UserRole`, `AuthProvider`, `RecordStatus`, `WorkshopType`, `MediaType`, `ProductType`, `MarketDemand`, `MakerType`, `TraditionType`, `ReviewRecordType`, `MediaProcessingJobType`, `MediaProcessingJobStatus`, `ProcessStepType`, `DataAccessTier`, `DataAccessStatus`, `GiStatus`, `CraftStatus`, `DesignWorkshopStatus`, `DwDictationConsent`, `DwOversightCapacity`, `DwAccessRequestStatus`, `DwAccessRequestSource`, `DwCodeRecordType`, `DwTokenRedemptionOutcome`, `DwTokenRedemptionReason`, `DwAiLayerKind`, `DwAiTier`, `DwAiDecision`, `ConsentState`, `ConsentSubjectType`, `ConsentPurpose`, `ConsentBasis`, `DwReviewRound`, `CredentialLinkPurpose`, `AccessStatus`, `UsageConsent`, `UsageConsentBasis`, `AadhaarVerifyStatus`, `AadhaarVerifyMethod`, `DuplicateKind`, `DuplicateStatus`, `DuplicateSource`.

## API surface

**501 operations** in the working tree — 239 GET, 173 POST, 37 DELETE,
27 PATCH, 25 PUT. 2 of them (`/health`, `/health/ready`) are declared
on the app rather than on a router; the rest are spread across `backend/app/api/routes/`:

| Route module | Operations |
|---|---|
| `design_workshops.py` | 49 |
| `media.py` | 21 |
| `questionnaire.py` | 21 |
| `workshops.py` | 21 |
| `design_workshop_oversight.py` | 19 |
| `questionnaire_forms.py` | 19 |
| `aadhaar.py` | 14 |
| `design_workshop_inspections.py` | 14 |
| `sanction_orders.py` | 13 |
| `usage.py` | 13 |
| `annual_plan.py` | 12 |
| `data_access.py` | 12 |
| `design_workshop_approvals.py` | 11 |
| `artisans.py` | 10 |
| `auth.py` | 10 |
| `designers.py` | 10 |
| `tasks.py` | 10 |
| `crafts.py` | 9 |
| `feedback.py` | 9 |
| `ministry_dashboard.py` | 9 |
| `ministry_exports.py` | 9 |
| `tools.py` | 8 |
| `artisan_duplicates.py` | 7 |
| `craft_categories.py` | 7 |
| `design_workshop_access.py` | 7 |
| `notifications.py` | 7 |
| `settings.py` | 7 |
| `two_factor.py` | 7 |
| `users.py` | 7 |
| `access.py` | 6 |
| `artisan_cards.py` | 6 |
| `cross_portal.py` | 6 |
| `map_coverage.py` | 6 |
| `ai_keys.py` | 5 |
| `answer_drafts.py` | 5 |
| `data_browser.py` | 5 |
| `datasets.py` | 5 |
| `export.py` | 5 |
| `processes.py` | 5 |
| `products.py` | 5 |
| `review.py` | 5 |
| `secrets.py` | 5 |
| `workshop_types.py` | 5 |
| `craft_codes.py` | 4 |
| `design_ratings.py` | 4 |
| `preferences.py` | 4 |
| `transcript_insights.py` | 4 |
| `app_release.py` | 3 |
| `asr_models.py` | 3 |
| `design_workshop_roster.py` | 3 |
| `design_workshop_viewers.py` | 3 |
| `ministry_stage_data_exports.py` | 3 |
| `reference.py` | 3 |
| `search.py` | 3 |
| `transcription_admin.py` | 3 |
| `cross_portal_search.py` | 2 |
| `design_workshop_copy.py` | 2 |
| `map_points.py` | 2 |
| `report_deliveries.py` | 2 |
| `analytics.py` | 1 |
| `completeness.py` | 1 |
| `dashboard.py` | 1 |
| `idempotency_keys.py` | 1 |
| `public.py` | 1 |

### Deployed versus tree

The number above counts decorators in this checkout. The number that matters operationally is what
the running API actually serves, which you read from the deployed schema rather than from the source:

```bash
curl -s https://d3ekigkotd1xa2.cloudfront.net/openapi.json \
  | python -c "import json,sys,collections; d=json.load(sys.stdin); \
      c=collections.Counter(m for p in d['paths'].values() for m in p if m in ('get','post','put','patch','delete')); \
      print(sum(c.values()), dict(c))"
```

A gap between the two is normal and means "not deployed yet". A gap in the other direction means
someone deployed from a branch.

> Note: that command only works while `BACKEND_EXPOSE_DOCS` is true on the deployment. The default
> is now **false** — see [SECURITY.md](SECURITY.md). Once it is false in production, count from a
> checkout of the deployed commit instead.

## Role ladder

- `CROWDSOURCE_VOLUNTEER` — rank **10**
- `FIELD_CONTRIBUTOR` — rank **20**
- `RESEARCHER` — rank **30**
- `DESIGNER` — rank **35**
- `INSPECTOR` — rank **37**
- `PROFESSOR` — rank **40**
- `ASSISTANT_DIRECTOR` — rank **42**
- `REGIONAL_DIRECTOR` — rank **45**
- `MINISTRY_ADMIN` — rank **48**
- `ADMIN` — rank **50**
- `MASTER_ADMIN` — rank **60**

Source of truth: `ROLE_RANK` in `backend/app/core/deps.py`, mirrored in
`frontend/lib/permissions.ts`. The two are checked against each other by this script.

## Transcription provider chain

Default order: 1. `elevenlabs`  2. `deepgram`  3. `whisper`  4. `muse` — `DEFAULT_STT_PROVIDER_ORDER`
in `backend/app/services/app_settings.py`. A master admin can reorder it at runtime; a provider with
no key is skipped wherever it sits.

## Automated tests

| Surface | Files | Cases | Runner |
|---|---|---|---|
| Backend unit (`backend/tests/`) | 336 | 6195 `def test_` | `python -m pytest -rf --durations=15` from `backend/` |
| Web end-to-end (`frontend/e2e/`) | 280 | 3206 `test(` | Playwright, `frontend/playwright.config.ts` |
| Android unit (`android/app/src/test/`) | 316 | 3811 `@Test` | `./gradlew :app:testDebugUnitTest` from `android/` |
| Android instrumented (`android/app/src/androidTest/`) | 9 | 25 `@Test` | needs a device; on demand only (`.github/workflows/android-emulator.yml`), never a gate |

The backend case count is `def test_` occurrences; pytest reports a larger number because
parametrised cases expand.
**The backend suite gates the deploy, the web suite half-gates it, and
neither gates a merge.** `Backend tests` — the job that runs the whole pytest suite — and `Web
typecheck, lint and unit specs`, which runs `npm run test:unit`, are two of the three names in the
`GATING_JOBS` list that `deploy-backend.yml` and `deploy-frontend.yml` each poll for at the SHA
being shipped, and neither workflow hands over to its `deploy` job until all three have concluded
green. The web one is only **half** a gate because `test:unit` is every `*-unit.spec.ts` bar two
excluded by name for wanting a dev server: the specs that drive a real screen are gated by nothing.
Merging is a separate question with a separate answer — required status checks live in branch
protection, which no file in a checkout can prove — so a red Checks still merges to `main`. See
[CI.md](CI.md) §1.1 and §5, and [QA_AUDIT.md](QA_AUDIT.md).

**THIS TABLE USED TO SAY `:app:testDebugUnitTest` REPORTS NO-SOURCE, AND IT WAS FALSE.** The string
was a hard-coded literal in the generator, and the counter beside it only read a flat directory —
which finds nothing in `src/test/java/com/…`, so the emptiness it reported was its own. The suite
runs: **1156 tests, 0 failures** on 2026-08-15. A generated fact is only as true as its generator,
and this one asserted an absence it had never looked for.

## Code volume

| Area | Tracked files | Tracked lines | Tree files | Tree lines |
|---|---|---|---|---|
| `backend/app` | 327 | 198,720 | 327 | 198,720 |
| `frontend/app` | 123 | 61,050 | 123 | 61,050 |
| `frontend/components` | 387 | 145,757 | 387 | 145,757 |
| `frontend/lib` | 187 | 82,587 | 187 | 82,587 |
| `android/app/src/main/java` | 355 | 254,530 | 355 | 254,530 |

Two columns because the two numbers get quoted interchangeably and disagree by however much work is
uncommitted. **Tracked** is `git ls-files`, which is the figure to use in a write-up — it is
reproducible from a clone. **Tree** includes files not yet committed, which is the figure to use when
reasoning about what is running locally. Neither is wrong; they answer different questions.

**REGENERATE THIS ON A CLEAN TREE.** Only the *file* columns come from the index; every *line* count
is read off disk, so `--write` on a dirty working copy writes uncommitted lines into the Tracked
column and quietly destroys the one property that column is for. It has already happened: the Android
row sat at 150 tracked files against 152 in the tree from the commit that added the divergence view
until 2026-08-19, because nobody re-ran `--write` after `git add`. **Two file columns that disagree
are the tell** — tracked and tree must be equal at a clean HEAD, so any gap between them means this
file was generated mid-change and its line counts belong to somebody's working copy.

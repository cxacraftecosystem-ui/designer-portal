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
| Prisma models | **72** |
| Prisma enums | **31** |
| `@@index` declarations | 226 |
| `@@unique` declarations | 24 |

Models: `User`, `AssignedTask`, `Feedback`, `FeedbackReport`, `UserPreference`, `AppRelease`, `Craft`, `Location`, `Artisan`, `Workshop`, `WorkshopArtisan`, `WorkshopCraft`, `ProductDocumentation`, `ToolDocumentation`, `ToolArtisan`, `ToolCraft`, `MediaFile`, `MediaProcessingJob`, `QuestionnaireSection`, `QuestionnaireSectionStatus`, `QuestionnaireQuestion`, `QuestionnaireInterview`, `QuestionnaireInterviewArtisan`, `QuestionnaireResponse`, `Questionnaire`, `QuestionnaireFormSection`, `QuestionnaireFormQuestion`, `QuestionnaireFormEntry`, `QuestionnaireFormAnswer`, `Process`, `ProcessStep`, `ReviewLog`, `AppSetting`, `WorkshopAssignment`, `ManagedSecret`, `UserAiCredential`, `SecretTestResult`, `DataAccessGrant`, `DataAccessScopeItem`, `EntryComment`, `RecordRevision`, `DesignWorkshop`, `DesignWorkshopViewer`, `DesignWorkshopInspector`, `DesignWorkshopOversight`, `DwArtisanImport`, `DesignWorkshopAccessRequest`, `RecordAccessToken`, `RecordAccessTokenRedemption`, `DesignWorkshopProvisionalMember`, `DwStageEntry`, `DwCustomSection`, `DwCustomField`, `DwReportExport`, `DwAiLayer`, `DwAiLayerDecision`, `DwWorkshopConsentDecision`, `DwInspectionFeedback`, `DwDictationDailyUsage`, `DwAiVerbDailyUsage`, `DwReviewRating`, `DesignerRoster`, `DesignerProfile`, `SanctionOrder`, `PasswordResetToken`, `AccessRoster`, `UsageEvent`, `UsageConsentDecision`, `AnnualPlanEntry`, `SanctionOrderDesigner`, `SanctionOrderImport`, `WorkshopTypeOption`.

Enums: `UserRole`, `AuthProvider`, `RecordStatus`, `WorkshopType`, `MediaType`, `ProductType`, `MarketDemand`, `MakerType`, `TraditionType`, `ReviewRecordType`, `MediaProcessingJobType`, `MediaProcessingJobStatus`, `ProcessStepType`, `DataAccessTier`, `DataAccessStatus`, `DesignWorkshopStatus`, `DwDictationConsent`, `DwOversightCapacity`, `DwAccessRequestStatus`, `DwAccessRequestSource`, `DwCodeRecordType`, `DwTokenRedemptionOutcome`, `DwTokenRedemptionReason`, `DwAiLayerKind`, `DwAiTier`, `DwAiDecision`, `DwReviewRound`, `CredentialLinkPurpose`, `AccessStatus`, `UsageConsent`, `UsageConsentBasis`.

## API surface

**349 operations** in the working tree — 173 GET, 106 POST, 28 DELETE,
24 PATCH, 18 PUT. 2 of them (`/health`, `/health/ready`) are declared
on the app rather than on a router; the rest are spread across `backend/app/api/routes/`:

| Route module | Operations |
|---|---|
| `design_workshops.py` | 43 |
| `workshops.py` | 21 |
| `media.py` | 20 |
| `questionnaire.py` | 20 |
| `questionnaire_forms.py` | 19 |
| `design_workshop_oversight.py` | 15 |
| `usage.py` | 13 |
| `data_access.py` | 12 |
| `sanction_orders.py` | 11 |
| `annual_plan.py` | 10 |
| `designers.py` | 10 |
| `tasks.py` | 10 |
| `feedback.py` | 9 |
| `ministry_dashboard.py` | 9 |
| `auth.py` | 8 |
| `tools.py` | 8 |
| `artisans.py` | 7 |
| `design_workshop_access.py` | 7 |
| `design_workshop_inspections.py` | 7 |
| `access.py` | 6 |
| `ai_keys.py` | 5 |
| `crafts.py` | 5 |
| `data_browser.py` | 5 |
| `datasets.py` | 5 |
| `export.py` | 5 |
| `processes.py` | 5 |
| `products.py` | 5 |
| `review.py` | 5 |
| `secrets.py` | 5 |
| `settings.py` | 5 |
| `users.py` | 5 |
| `workshop_types.py` | 5 |
| `app_release.py` | 3 |
| `asr_models.py` | 3 |
| `design_ratings.py` | 3 |
| `design_workshop_viewers.py` | 3 |
| `map_points.py` | 2 |
| `preferences.py` | 2 |
| `reference.py` | 2 |
| `analytics.py` | 1 |
| `dashboard.py` | 1 |
| `public.py` | 1 |
| `search.py` | 1 |

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

Default order: 1. `elevenlabs`  2. `deepgram`  3. `whisper` — `DEFAULT_STT_PROVIDER_ORDER`
in `backend/app/services/app_settings.py`. A master admin can reorder it at runtime; a provider with
no key is skipped wherever it sits.

## Automated tests

| Surface | Files | Cases | Runner |
|---|---|---|---|
| Backend unit (`backend/tests/`) | 221 | 4594 `def test_` | `python -m pytest -q` from `backend/` |
| Web end-to-end (`frontend/e2e/`) | 198 | 2439 `test(` | Playwright, `frontend/playwright.config.ts` |
| Android unit (`android/app/src/test/`) | 231 | 3095 `@Test` | `./gradlew :app:testDebugUnitTest` from `android/` |
| Android instrumented (`android/app/src/androidTest/`) | 8 | 24 `@Test` | needs a device; not run in CI |

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
| `backend/app` | 204 | 151,706 | 204 | 151,706 |
| `frontend/app` | 92 | 51,688 | 92 | 51,688 |
| `frontend/components` | 300 | 129,126 | 300 | 129,126 |
| `frontend/lib` | 125 | 66,616 | 125 | 66,616 |
| `android/app/src/main/java` | 260 | 228,194 | 260 | 228,194 |

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

# Environment variables — handover reference

**Who this is for:** a developer picking this project up for the first time. It answers, for every
service, "which variables exist, which ones must I set, what happens if I don't, and which ones are
secrets I must never commit or paste into a ticket".

Read it top to bottom once; after that use it as a lookup table. Deployment click-paths live in
[DEPLOYMENT_VERCEL.md](DEPLOYMENT_VERCEL.md) (web) and
[../backend/DEPLOY_AWS.md](../backend/DEPLOY_AWS.md) (API, storage, CI).

---

## Where each file lives

| File | Loaded by | Committed? | Contains secrets? |
|---|---|---|---|
| `backend/.env` | FastAPI (`pydantic-settings`, `app/core/config.py`) and `backend/scripts/*.py` | **No** (gitignored) | **Yes** — DB URL, JWT secret, AWS keys, AI keys |
| `backend/.env.example` | nothing — template | Yes | No, placeholders only |
| `frontend/.env.local` | `next dev` / `next build` | **No** (gitignored via `*.local`) | No — everything in it ships to the browser |
| `frontend/.env.local.example` | nothing — template for local dev | Yes | No |
| `frontend/.env.example` | nothing — template documenting the production shape | Yes | No |
| `.env.example` (repo root) | nothing — aggregate reference of every variable in the monorepo | Yes | No |
| `android/local.properties` | Gradle | **No** (gitignored) | No |
| Vercel dashboard | the frontend build (via `vercel pull` on the CI runner) | n/a | No — see the "public means public" rule below, and rule 3 on the variable **type** |
| GitHub Actions secrets | `.github/workflows/*` | n/a | **Yes** — at least `BACKEND_ENV`, `EC2_SSH_KEY`, `EC2_HOST`. **No database URL is set**, which is the fact the dormant keep-alive turns on. That comes from a `gh secret list` recorded in `.github/workflows/keep-supabase-active.yml`'s header, whose full reading was "those three and nothing else" — **but that inventory is undated and cannot be current as written**, because the Actions table below lists `VERCEL_TOKEN`/`VERCEL_ORG_ID`/`VERCEL_PROJECT_ID` as required and quotes two of their values. See the note under that table before relying on either. |
| `backend/.env.production` | nothing in this repository loads it; it is the developer's local copy of what production runs | **No** (gitignored by `.gitignore:23`, `.env.*`) | **Yes** — and it is this repository's authority for which provider hosts the production database. See "The database" below; establish facts about it by **host substring only** |

Setup, in order, for a fresh machine:

```powershell
docker compose up -d                                    # Postgres :55432, MinIO :9000
cd backend;  Copy-Item .env.example .env                # then edit
cd ..\frontend; Copy-Item .env.local.example .env.local # then edit
```

### Three rules that cause most of the confusion

1. **`NEXT_PUBLIC_*` is not private.** Next.js inlines those values into the JavaScript bundle at
   build time. Anyone can read them in devtools, and changing one requires a **rebuild/redeploy** to
   take effect. Never put a real secret behind that prefix.
2. **The backend caches its settings.** `get_settings()` is `@lru_cache`d, so a running uvicorn
   process never notices an edited `.env`. Always restart (`sudo systemctl restart fieldrepo
   fieldrepo-queue`, or Ctrl-C the dev server) after a change.
3. **Several identifiers here still read `fieldrepo` / `design-workshop`, and that is correct — but
   READ THE NEXT RULE BEFORE ACTING ON IT.** The product was renamed to Design Prototype Workshop;
   the *things* were not. The systemd units (`fieldrepo`, `fieldrepo-queue`) are installed and enabled
   on the API box and are what `deploy-backend.yml` restarts by name; the Vercel project owns the
   production domain. Treat them as opaque identifiers: aligning them with the product name is a
   migration with a cutover, never an edit to a document or a default. See
   `infra/terraform/variables.tf` and `infra/terraform/user_data.sh` for the specific failure each
   rename would cause.

4. **THIS PORTAL HAS ITS OWN INFRASTRUCTURE, AND THE SHARED NAMES ABOVE ARE WHY THAT IS EASY TO MISS.**
   The designer portal was derived from the field repository, so both repositories carry a
   byte-identical `deploy-backend.yml` and a `user_data.sh` that HARDCODES the unit names rather than
   templating them from `var.project`. The consequence is a genuine trap: two different EC2 boxes each
   run services literally called `fieldrepo`, and nothing in a unit name, an nginx site name or a
   workflow file distinguishes them. The only thing that decides which machine a push touches is the
   `EC2_HOST` secret in whichever repository is being pushed.

   Separate infrastructure was provisioned for this portal on 2026-08-16 (terraform
   `-var="project=designrepo"`), and it is separate at every layer that holds data:

   | | Designer portal (this repo) | Field repository |
   |---|---|---|
   | API box | `13.206.216.18` | a different Elastic IP |
   | S3 media bucket | `designrepo-media-626159998512` | `fieldrepo-media-626159998512` |
   | CloudFront | `d3ekigkotd1xa2.cloudfront.net` (origin id `designrepo-ec2-origin`) — **UNRESOLVED**, see below | its own distribution |
   | SSH key pair | `designrepo-deploy` | its own |
   | Vercel | `design-repository.vercel.app` | its own project |
   | Google OAuth | its own web + Android clients (see `2696bfb`) | its own |
   | systemd units | **`fieldrepo`, `fieldrepo-queue`** | **`fieldrepo`, `fieldrepo-queue`** |

   **The last row is the one to remember.** The unit names are identical on both boxes because
   `user_data.sh` writes `/etc/systemd/system/fieldrepo.service` as a literal string; they are shared
   by hardcoding, not because the two deployments are coupled. So `systemctl restart fieldrepo` in
   `deploy-backend.yml` is correct here, and correcting it to `designrepo` would break this portal's
   deploy against a unit that does not exist.

   **What must never be done: copying `EC2_HOST`, `EC2_SSH_KEY` or `BACKEND_ENV` between the two
   repositories.** Before 2026-08-16 this portal had no deploy secrets at all and its backend deploy
   had been failing since 8 August on an empty SSH key. Wiring it up by copying the field
   repository's values across would have rsynced this portal's backend over
   `/home/ubuntu/app/backend/` on the field repository's live box, overwritten its `.env`, stopped its
   `fieldrepo` service and run migrations against its database — with a workflow file that looks
   entirely correct while doing it, because every name in it matches. That is not recoverable by a
   revert.

   **UNRESOLVED — WHICH CLOUDFRONT DISTRIBUTION IS THIS PORTAL'S? Recorded 2026-08-22; needs a
   console, not a checkout.** The CloudFront row above says `d3ekigkotd1xa2.cloudfront.net`. Every
   other statement of an API host in this repository — the `NEXT_PUBLIC_API_URL` production column
   in this document's own web section, the `apiBaseUrl` row in its Android section,
   `android/app/build.gradle.kts`'s compiled-in default, `android/app/src/main/res/xml/network_security_config.xml`,
   `.env.example`, `../README.md`, `DEPLOYMENT_VERCEL.md`, `CDN.md`, `ARCHITECTURE.md`,
   `../backend/DEPLOY_AWS.md` — says `d2b34i3e92al6i.cloudfront.net`, which `../README.md` pairs
   with the Elastic IP `15.207.145.174`. That IP is the **field repository's** box: this portal's is
   `13.206.216.18`, per the row above and per the `designrepo` Terraform workspace state.

   **Nothing in the repository settles it, and that is a fact about the repository rather than a gap
   in the reading.** `infra/terraform/main.tf` creates S3, IAM, EC2, a security group and an Elastic
   IP, and **no CloudFront distribution at all** — [CDN.md](CDN.md) says the distribution is
   maintained "AWS console, by hand". So the only repository evidence for either pairing is prose,
   and the prose disagrees with itself. The other rows of the table above are independently
   corroborated (`infra/terraform/terraform.tfstate.d/designrepo/terraform.tfstate` for the IP,
   bucket and key pair; `frontend/.vercel/project.json` for the Vercel project), which is why the
   CloudFront row is the suspicious one rather than the trusted one — but corroboration of its
   neighbours is not evidence for it.

   **One more measurement, taken 2026-08-22, and it cuts against the row rather than for it:**
   `d3ekigkotd1xa2` appears in **exactly one place in the whole repository — the row above.** A
   recursive search of every tracked `.md`, `.tf`, `.kts`, `.xml` and `.example` returns that line and
   this block and nothing else, while `d2b34i3e92al6i` is written into at least seven files including
   two the clients actually compile against. A single unsupported witness is not proof the row is
   wrong — the row could be the one place somebody recorded a console fact correctly, which is
   precisely what an infrastructure table is FOR — but it does mean nobody should read the row's
   presence in a table as independent confirmation. It is one sentence, by one author, once.

   **Do not "fix" one side of this.** The handset default and the committed web production value are
   the same literal, so the two clients agree with each other today and a working client is what a
   half-change breaks. Two answers, and the whole of each:

   * **If `d3ekigkotd1xa2` is this portal's** — the clients are pointed at the field repository's
     API and the fix is one pass over every file listed above, plus the `NEXT_PUBLIC_API_URL`
     variable in the Vercel dashboard (which no commit can change), plus a re-issued APK, because
     the old default is compiled into every build already on a phone.
   * **If `d2b34i3e92al6i` is this portal's** — the clients are right and the CloudFront row above is
     wrong; correct the row and say what `d3ekigkotd1xa2` actually is.

   **The one command that answers it**, from a machine with the AWS credentials:
   `aws cloudfront list-distributions --query "DistributionList.Items[].{d:DomainName,o:Origins.Items[0].DomainName,id:Origins.Items[0].Id}" --output table`.
   Whichever distribution's origin resolves to `13.206.216.18` is this portal's. Write the answer
   into the row above, then delete this block — `docs/tools/check-docs.mjs`'s `checkAndroidApiHost`
   fails the docs run if this question outlives its answer, and fails it the other way if the two
   hosts drift apart again with nothing recorded.

5. **In Vercel, every `NEXT_PUBLIC_*` variable must be type `Encrypted`, never `Sensitive`.**
   Sensitive is write-only: Vercel will not return that value to anyone afterwards, including to the
   `vercel pull` our CI runs before it builds. Because the build happens on a GitHub runner rather
   than on Vercel, a sensitive variable simply is not present when Next.js compiles, and Next.js
   inlines `undefined` instead of failing — a green pipeline over a site that cannot log anyone in.
   And it buys nothing even in principle, since rule 1 says the value is published to every visitor
   regardless. Audit it with `vercel env ls production`; the full story is
   [DEPLOYMENT_VERCEL.md §2.2](DEPLOYMENT_VERCEL.md).

---

## The database: what is required, and where it runs today

**These are two different facts, and mixing them is what made a provider migration rewrite thirty
files.** Everything above the line is a property of the application and travels with it. Everything
below the line is a property of one deployment and changes without a code change.

### Requirement — PostgreSQL, and nothing more specific than that

`backend/prisma/schema.prisma` declares `provider = "postgresql"`. Nothing narrower is required:

| | |
|---|---|
| Engine | **PostgreSQL**. Any provider, any host, managed or self-run. |
| Version | 16 is what `docker-compose.yml` runs locally (`postgres:16-alpine`) and what the migrations are developed against. Nothing in the schema needs a 16-only feature. |
| Extensions | **None.** `grep -r "CREATE EXTENSION" backend/prisma/migrations/` returns one hit and it is inside a comment explaining why `pg_trgm` was *not* adopted. A stock server is enough. |
| Connection | One `DATABASE_URL` in libpq/Prisma form. TLS for any non-loopback host — see `DATABASE_REQUIRE_SSL` below. |
| Pooling | The application does not require an external pooler. Prisma opens `DATABASE_CONNECTION_LIMIT` connections per worker; whether those land on a pooler or on the server is the deployment's business. |

So "which provider" is never the answer to "will this run". If a candidate speaks PostgreSQL and
accepts a connection string, it is a candidate. **Requirement text anywhere in this repository —
docs, comments, READMEs — should say PostgreSQL and stop there.**

### Deployment — where it happens to run, stated once

**Production runs on Neon.** *Recorded 2026-08-22.*

| | |
|---|---|
| Authority | `backend/.env.production` — it names a `neon.tech` host and no other database provider's. That file is the only thing that decides this, and it is gitignored, so **this table is a report of it, never a second source of truth.** |
| How to re-check without reading a secret | `grep -c neon.tech backend/.env.production` → a line count, never a line. That is the whole method: **presence of a host substring**, which is all the evidence this fact needs and the only evidence that can be gathered without handling a credential. Do not open the file, do not paste from it, do not connect with it. |
| What else is in there | The same file also names an `amazonaws` host. That is **not a second database**: §8c tests for the `rds.amazonaws.com` substring among its known provider hosts and does not match, so whatever that AWS host is, it is not AWS's managed-PostgreSQL endpoint — it is the S3 media storage documented in the AWS section below. Established the same way as everything else here: by substring, without reading a line. |
| Asserted by | `docs/tools/check-docs.mjs` §8c, which reads that same host substring and nothing else, and fails this run if the sentence above stops matching it. |
| Previously | Supabase, until the move recorded here. `backend/.env.supabase.bak` is the leftover of that migration and is why the old provider is still named in historical notes throughout these docs — see [RESEARCH_NOTES.md](RESEARCH_NOTES.md) and [QA_AUDIT.md](QA_AUDIT.md), where the incidents that shaped today's connection settings were measured against it. |

**The one place a provider name legitimately appears is the row above.** Everywhere else, if a
sentence names a provider, it is either (a) describing a past incident, in which case it must carry
the date and be written in the past tense, or (b) wrong.

**What the runtime code knows about the provider: nothing, as of 2026-08-22.** Both files that touch
the DSN say so in their own headers — `backend/app/core/config.py`'s reads "THIS FILE NAMES NO
DATABASE PROVIDER, AND MUST NOT LEARN ONE", and `backend/app/core/db.py`'s reads "THIS MODULE KNOWS
THAT THE DATABASE IS POSTGRESQL AND NOTHING ELSE ABOUT IT". Each of them **used to** know one:
`build_runtime_database_url` matched the host suffix `.pooler.supabase.com` and rewrote `:5432` to
`:6543`, and the pool ceiling rode on that same match — so when the deployment moved, the ceiling
silently stopped applying. Every decision either file makes now turns on **shape** (is the host
loopback or private? — `_is_local_db_host`) or on an explicit setting.

**That is a change in what the settings mean, not just in the code.** `DATABASE_USE_TRANSACTION_POOLER`
is no longer a no-op against an unrecognised host: it applies to **every** remote DSN, so it has to be
set to match the endpoint you actually pointed `DATABASE_URL` at. See its row below, which is the
operator-facing half of this paragraph.

---

## Backend — FastAPI (`backend/.env`)

Source of truth: `backend/app/core/config.py`. "Default" is the value the code uses when the
variable is absent; a blank default means the app **refuses to start** without it.

### Database

| Variable | Required | Default | Secret | Notes |
|---|---|---|---|---|
| `DATABASE_URL` | **Yes** | — | **Yes** | Prisma/PostgreSQL connection string, in libpq form. **Two different consumers read this one name, and on a provider with two endpoints they want different values.** `prisma migrate deploy` reads it *raw* out of `schema.prisma`'s `env("DATABASE_URL")` — never through `Settings` — and needs a connection that supports **SESSION** mode, because it takes advisory locks and runs DDL that transaction pooling cannot hold across statements. The running app reads it through `Settings` and wants the **pooled** endpoint, declared as such with `DATABASE_USE_TRANSACTION_POOLER` below. Which value each process gets is a deployment arrangement, not a code decision: see `backend/app/core/db.py`'s header, and `infra/k8s/base/job-migrate.yaml`, which gives the migration Job the secret's value with no app ConfigMap attached. On a provider with a single endpoint, or a direct server, there is one value and the flag below goes `false`. |
| `DATABASE_USE_TRANSACTION_POOLER` | No | `true` | No | **A declaration about the endpoint `DATABASE_URL` names — not a router, and it matches no hostname.** True means "this DSN is a transaction-mode pooler", and `build_runtime_database_url` (`backend/app/core/db.py`) adds `pgbouncer=true` so the query engine stops relying on session-pinned named prepared statements that transaction pooling cannot keep. It takes effect on **any remote host**; a loopback/private DSN is returned untouched, and a `pgbouncer` already written into the URL always wins. **Set it `false` when `DATABASE_URL` is a direct, non-pooling endpoint** — including a provider that publishes only one endpoint. Default true because the two mistakes are not symmetric: true-against-direct costs prepared-statement caching, false-against-pooled fails queries under load. It **used to** be a host-suffix rewrite for one vendor's pooler (`.pooler.supabase.com`, `:5432 → :6543`); that was removed on 2026-08-22 and both files' headers record why. |
| `DATABASE_CONNECTION_LIMIT` | No | `10` | No | Client connections **per uvicorn worker**. Do not raise to 40: on the Supabase deployment this ran on until 2026-08-22 that tripped a 200-client pooler ceiling (`EMAXCONN`) and crash-looped startup. The *ceiling* was that provider's; the *lesson* — that this number multiplies by every worker, pod and rollout surge — is arithmetic and applies everywhere. **Whatever the current provider's ceiling is has not been established from this repository; see the open question in [KUBERNETES.md](KUBERNETES.md).** |
| `DATABASE_POOL_TIMEOUT` | No | unset → Prisma's own (10 s) | No | Seconds to wait for a pooled connection. |
| `DATABASE_REQUIRE_SSL` | No | unset → automatic | No | Forces `sslmode=require` on/off. Unset means: append it for a **remote** host, leave a loopback/private host alone (docker-compose Postgres ships no certificate). A URL that already carries an `ssl*` parameter always wins. Matters because libpq/Prisma default to `sslmode=prefer`, which silently falls back to plaintext. |

### Auth

| Variable | Required | Default | Secret | Notes |
|---|---|---|---|---|
| `JWT_SECRET` | **Yes** | — | **Yes** | HMAC key signing every access token. Generate with `python -c "import secrets; print(secrets.token_urlsafe(48))"`. Rotating it logs every user out on web and Android. |
| `JWT_EXPIRES_MINUTES` | No | `10080` (7 days) | No | Access-token lifetime. |
| `JWT_ALGORITHM` | No | `HS256` | No | Only `HS256`/`HS384`/`HS512` are accepted; anything else (notably `none`, or an `RS*`/`ES*` algorithm) makes the app refuse to start. That is the algorithm-confusion guard — see `Settings._normalise_jwt_algorithm`. |
| `ALLOW_WEAK_JWT_SECRET` | No | `false` | No | **Local development only.** Lets the API boot with a short/placeholder `JWT_SECRET` instead of refusing to start (`app/core/security.py::verify_jwt_configuration`). Never set it in a deployed environment — a guessable secret lets anyone mint a master-admin token. |
| `SECRETS_ENCRYPTION_KEY` | No | unset → derived from `JWT_SECRET` | **Yes** | Fernet key encrypting the runtime-editable provider keys stored in `ManagedSecret` (`app/services/managed_secrets.py`). Left unset it is derived deterministically from `JWT_SECRET`, which is why the feature needs no setup — but it also means **rotating `JWT_SECRET` makes every stored secret undecryptable** and each one has to be re-entered in the Settings hub. Set it explicitly *before* you ever rotate. Accepts a Fernet key or any high-entropy passphrase. |

### Authenticated-identity cache

`get_current_user` reads the user row on **every** authenticated request, and that read is one
cross-region round trip (200–400 ms) before any of the request's real work starts. This cache removes
it for a few seconds at a time. Unlike everything under `SCALE_*`, it is **on by default**, because
the cost it removes is being paid right now and a flag that had to be switched on would leave it
there.

Read [SECURITY.md §4.1](SECURITY.md) before changing any of these — the row being cached is the one
authorisation decisions read, so the TTL is a revocation window.

| Variable | Required | Default | Secret | Notes |
|---|---|---|---|---|
| `AUTH_USER_CACHE_ENABLED` | No | `true` | No | `false` restores one query per request. **This is the kill switch**: if the cache is ever suspected of serving a stale role during an incident, this reverts the behaviour with a restart and no deploy. |
| `AUTH_USER_CACHE_TTL_SECONDS` | No | `5.0` | No | Seconds, and deliberately single-digit. Writes *this process* makes invalidate explicitly and have no window at all; the TTL is only the backstop for writes it cannot see — a `psql` session, the seed script, another worker. Every second added here is a second a demoted or deleted account keeps working after such a write. |
| `AUTH_USER_CACHE_MAX_ENTRIES` | No | `512` | No | LRU ceiling. A cached row is the `User` model's scalar columns, order 1 KB, so 512 identities is well under a megabyte on a 1 GiB box. |

### API schema exposure

| Variable | Required | Default | Secret | Notes |
|---|---|---|---|---|
| `BACKEND_EXPOSE_DOCS` | No | **`false`** | No | Serve `/docs`, `/redoc` and `/openapi.json`. FastAPI serves all three to anyone by default; the schema names every route, parameter and model field including admin-only ones. Closed by default because the production `.env` lives in a GitHub secret this repository cannot edit, so a default-on flag would leave them exposed exactly where it matters. Set `true` for local development. See [SECURITY.md §1.4](SECURITY.md). |

### Security response headers

Emitted by `app.main.SecurityHeadersMiddleware`. Defaults are correct for local development.

| Variable | Required | Default | Secret | Notes |
|---|---|---|---|---|
| `SECURITY_HSTS_ENABLED` | No | `true` | No | Emit `Strict-Transport-Security`. `false` removes the header entirely. |
| `SECURITY_HSTS_MAX_AGE` | No | `63072000` (2 years) | No | HSTS `max-age`, in seconds. |
| `SECURITY_FORCE_HSTS` | No | `false` | No | **Set `true` on the EC2 box.** Production is browser →HTTPS→ CloudFront →HTTP→ nginx →HTTP→ uvicorn, and nginx overwrites `X-Forwarded-Proto` with its own scheme, so the app cannot otherwise tell the viewer used TLS and never emits HSTS. |

### Object storage

| Variable | Required | Default | Secret | Notes |
|---|---|---|---|---|
| `AWS_ACCESS_KEY_ID` | **Yes** | — | Treat as secret | IAM user (or MinIO account) with `PutObject`/`GetObject`/`DeleteObject` on the media bucket. Local MinIO: `minioadmin`. |
| `AWS_SECRET_ACCESS_KEY` | **Yes** | — | **Yes** | Local MinIO: `minioadmin`. |
| `AWS_REGION` | No | `us-east-1` | No | Production: `ap-south-1`. Must match the bucket's region or presigned URLs 403. |
| `AWS_S3_BUCKET` | **Yes** | — | No | Production: `designrepo-media-626159998512` — THIS portal's own bucket, not the field repository's `fieldrepo-media-626159998512`. Local: `design-workshop`. |
| `AWS_S3_ENDPOINT` | No | unset | No | **Set only for MinIO/non-AWS storage** (`http://localhost:9000`). Leave it UNSET on AWS so boto3 signs against the dual-stack regional endpoint — that is what makes uploads work from IPv6-only mobile networks. |
| `AWS_S3_PUBLIC_BASE_URL` | No | unset | No | Base URL used to build readable media links. On AWS use the dual-stack host for THIS portal's bucket: `https://designrepo-media-626159998512.s3.dualstack.ap-south-1.amazonaws.com`. |
| `AWS_S3_SSE_ALGORITHM` | No | `AES256` | No | Server-side encryption requested on uploads the **API** starts (multipart create). `aws:kms` needs a key policy granting the media IAM user. Set it **empty** for local MinIO without a KMS backend, which rejects the header outright. Presigned single PUTs cannot carry it — the bucket's default-encryption setting covers those. |

### Offline speech model artifacts

| Variable | Required | Default | Secret | Notes |
|---|---|---|---|---|
| `ASR_MODEL_DIR` | No | **unset** | No | Directory the API reads ASR model files out of, one subdirectory per artifact id. Unset means `/api/asr-models` reports every artifact as unpublished and the byte routes answer **503** — a deployment that has not been given the bytes must say so, never serve a short body. A blank value counts as unset, so `ASR_MODEL_DIR=""` cannot resolve to the process's working directory. A filesystem path rather than an object-storage key **on purpose**: the SHA-256 this endpoint publishes is computed from the file on each change, so there is no stored copy of it to drift, and that is only affordable against a local read. The bytes are not in git (365 MB) — the operator step that places them, and the Kubernetes shapes for it, are in [ASR-MODEL-HOSTING.md](ASR-MODEL-HOSTING.md) §3. |

### Web origins and CORS

| Variable | Required | Default | Secret | Notes |
|---|---|---|---|---|
| `NEXT_PUBLIC_APP_URL` | No | `http://localhost:3000` | No | Public origin of the web app. Same name as the frontend variable so one `.env` can feed both. |
| `BACKEND_CORS_ORIGINS` | No | `http://localhost:3000` | No | **Comma-separated exact origins** allowed to call the API from a browser. No trailing slash, no path, no wildcard. Every Vercel production/preview/custom domain must be listed or the browser blocks the preflight. |

### Identity and roles

| Variable | Required | Default | Secret | Notes |
|---|---|---|---|---|
| `GOOGLE_CLIENT_ID` | No | unset | No | Google **web** OAuth client ID; ID tokens from web and Android are verified against it. Same value as the frontend's `NEXT_PUBLIC_GOOGLE_CLIENT_ID`. Unset ⇒ Google login rejected. |
| `GOOGLE_ANDROID_CLIENT_ID` | No | unset | No | Extra accepted audience if Android tokens arrive with the Android client ID. |
| `MASTER_ADMIN_EMAIL` | **Yes** | — | No | Google account permanently at `MASTER_ADMIN` (rank 60). The app will not start without it. |
| `MASTER_ADMIN_NAME` | No | `Ankit Kumar` | No | Display name for that account. |
| `DEFAULT_SIGNUP_ROLE` | No | `CROWDSOURCE_VOLUNTEER` | No | Tier given to brand-new self-registered Google accounts on the seven-tier ladder. Set `RESEARCHER` to restore the old open-signup behaviour. |

### Speech-to-text and AI (all optional)

Providers are tried highest-priority-first with automatic failover. With **none** of these set,
uploads still succeed — transcripts and grid measurements simply stay empty.

| Variable | Required | Default | Secret | Notes |
|---|---|---|---|---|
| `ELEVENLABS_API_KEY` | No | unset | **Yes** | Priority 1: ElevenLabs Scribe. Auto language detection, ~1 GB files, no chunking. |
| `ELEVENLABS_STT_MODEL` | No | `scribe_v1` in config, but the **effective** model is `scribe_v2` | No | Read the note below before setting it. |
| `DEEPGRAM_API_KEY` | No | unset | **Yes** | Priority 2: Deepgram Nova-3. Handles code-switched Hindi + English. |
| `DEEPGRAM_STT_MODEL` | No | `nova-3` | No | |
| `OPENAI_API_KEY` | No | unset | **Yes** | Primary role is transcript **refinement/translation**; only transcribes when neither dedicated STT key is set (priority 3, Whisper). |
| `OPENAI_TRANSCRIPTION_MODEL` | No | `whisper-1` | No | |
| `OPENAI_CHAT_MODEL` | No | `gpt-4o-mini` | No | Rewrites raw transcripts into clean interviewer/interviewee dialogue. |
| `GEMINI_API_KEY` | No | unset | **Yes** | Single legacy key for grid measurement. |
| `GEMINI_API_KEYS` | No | `""` | **Yes** | Any number of comma- or newline-separated keys; the worker rotates and fails over across them. Combined with `GEMINI_API_KEY` and de-duplicated. |
| `GEMINI_MEASUREMENT_MODEL` | No | `gemini-2.5-flash-lite` | No | Pin an id that still exists — `gemini-1.5-flash` now 404s. |
| `NEXT_PUBLIC_MAPTILER_API_KEY` | No | unset | No | Read by the backend only so one `.env` can feed both apps; the browser gets it from the frontend build. |

> **`ELEVENLABS_STT_MODEL` is the one variable in this file whose config default is not what runs.**
> `config.py` defaults it to `scribe_v1`, which was the only model that existed when the integration
> was written. `_elevenlabs_model` in `app/services/ai.py` treats that specific value as "unset" and
> uses **`scribe_v2`**; setting anything else uses your value verbatim. So the effective default is
> `scribe_v2`, and writing `ELEVENLABS_STT_MODEL=scribe_v1` into a `.env` does **not** pin the old
> model — it is indistinguishable from leaving it blank. (`scribe_v1` is still used as the
> conservative retry when a `scribe_v2` request is rejected.) A deployment that genuinely needs the
> legacy model must change the code, not the variable.

Provider ordering is a **runtime** setting, not an environment variable: a master admin ranks the
three providers in the Settings hub, stored on `AppSetting`. The default order is generated into
[REPO_FACTS.md](REPO_FACTS.md). A provider whose key is unset is skipped wherever it is ranked, and
keys resolve through the managed-secret layer, so adding one in the UI extends the chain immediately
with no restart. Full semantics: [ARCHITECTURE.md §6](ARCHITECTURE.md).

### Media processing queue

| Variable | Required | Default | Secret | Notes |
|---|---|---|---|---|
| `MEDIA_QUEUE_WORKER_ENABLED` | No | `true` | No | **Must be `false` on the production web process** — a separate `fieldrepo-queue` systemd unit runs `python -m app.worker` so ffmpeg + AI work never blocks HTTP requests. `true` is right for local dev. |
| `MEDIA_QUEUE_INTERVAL_SECONDS` | No | `5.0` | No | Poll interval between queue sweeps. |
| `MEDIA_QUEUE_BATCH_SIZE` | No | `3` | No | Jobs claimed per sweep. |
| `MEDIA_QUEUE_JOB_MAX_ATTEMPTS` | No | `3` | No | Retries before a job is marked failed. Provider throttling (HTTP 429/503) requeues **without** burning an attempt. |

### Optional scaling layer — `SCALE_*` and the read replica

**Every one of these is off or unset by default**, and a fresh clone that sets none of them runs
exactly the code paths it runs today. The package they configure is not imported on the request path
until a route opts in.

They are **not tabulated here**, deliberately: `backend/app/scale/README.md` documents each one — what
it buys, what it costs, how to verify it — and [SCALABILITY.md](SCALABILITY.md) explains when any of
them is the right answer. Duplicating them would create a second place to be wrong.

The names, so this file remains a complete index of what `config.py` reads:

- Response cache: `SCALE_CACHE_ENABLED`, `SCALE_CACHE_BACKEND`, `SCALE_CACHE_TTL_SECONDS`,
  `SCALE_CACHE_MAX_ENTRIES`, `SCALE_CACHE_MAX_BYTES`, `SCALE_CACHE_MAX_ENTRY_BYTES`,
  `SCALE_CACHE_SINGLEFLIGHT_TIMEOUT_SECONDS`
- Redis backend: `SCALE_REDIS_URL`, `SCALE_REDIS_TIMEOUT_SECONDS`
- Rate limiting: `SCALE_RATE_LIMIT_ENABLED`, `SCALE_RATE_LIMIT_REQUESTS`,
  `SCALE_RATE_LIMIT_WINDOW_SECONDS`
- Pagination and counts: `SCALE_KEYSET_PAGINATION_ENABLED`, `SCALE_APPROX_COUNT_ENABLED`,
  `SCALE_APPROX_COUNT_THRESHOLD`
- `DATABASE_READ_REPLICA_URL` — unset by default; a replica connection string for read-only queries.

### Not read by `config.py`

| Variable | Read by | Required | Default | Secret | Notes |
|---|---|---|---|---|---|
| `ADMIN_EMAIL` | `backend/scripts/seed_admin.py` | No | `admin@example.com` | No | First email/password admin, so you can log in before Google OAuth exists. |
| `ADMIN_NAME` | same | No | `Repository Admin` | No | |
| `ADMIN_PASSWORD` | same | No (script skips without it) | — | **Yes** | Change it before any real data is entered. |
| `SUPABASE_REST_URL` | nothing today | No | — | No | **Historical, 2026-08-22.** Left from the Supabase deployment. Nothing in this repository reads any of these three, and production is not on that provider — see "The database" above. They cost nothing to leave and would cost a reader a search to explain if deleted silently; delete them the next time `backend/.env.example` is revised. |
| `SUPABASE_PUBLISHABLE_KEY` | nothing today | No | — | No | Same. Anon/publishable key. |
| `SUPABASE_SECRET_KEY` | nothing today | No | — | **Yes** | Same. Service-role key; if a value is still sitting in any `.env`, rotate it at the provider and remove it. |

---

## Frontend — Next.js (`frontend/.env.local`, or the Vercel dashboard)

**Three** variables are read by application code, and **all of them are public** (see rule 1 above):
`NEXT_PUBLIC_API_URL`, `NEXT_PUBLIC_GOOGLE_CLIENT_ID` and `NEXT_PUBLIC_MAPTILER_API_KEY`. A fourth,
`NEXT_PUBLIC_APP_URL`, is documented here because the **backend** reads it — no frontend code does.

Re-verify the list rather than trusting it, since a new page can add one at any time:

```bash
grep -rhoP "process\.env\.[A-Z_0-9]+" frontend/app frontend/components frontend/lib \
  frontend/next.config.ts | sort -u
```

As of 2026-07-27 that returns exactly those three plus `NODE_ENV` (in `next.config.ts`). The
Playwright scripts additionally read three `PW_*` variables from the shell, but they are never
bundled.

| Variable | Required | Default in code | Local value | Production value | Secret | Notes |
|---|---|---|---|---|---|---|
| `NEXT_PUBLIC_API_URL` | **Yes** in production | `http://localhost:8000` | `http://localhost:8000` | `https://d2b34i3e92al6i.cloudfront.net` | No | **ORIGIN ONLY.** `lib/api.ts` appends `/api` itself, so a trailing `/api` or `/` makes every request 404. Must be `https://` in production or the browser blocks it as mixed content. |
| `NEXT_PUBLIC_APP_URL` | No (frontend) | n/a — **no frontend code reads it** | `http://localhost:3000` | your Vercel/custom domain | No | Shares its name with the backend variable so one `.env` can feed both; only the backend (`config.py`) actually reads it. Setting it in Vercel changes nothing today — it is there so the value stays in sync with the backend and with `BACKEND_CORS_ORIGINS`. |
| `NEXT_PUBLIC_GOOGLE_CLIENT_ID` | No | none | blank | Google web client ID | No | Blank hides the Google button and leaves email/password login. Must equal the backend's `GOOGLE_CLIENT_ID`, and the origin must be an "Authorized JavaScript origin" on that client or GSI returns 403. |
| `NEXT_PUBLIC_MAPTILER_API_KEY` | No | none | blank | MapTiler key | No (restrict by domain) | Blank ⇒ the map coordinate picker degrades to manual latitude/longitude entry. Never blocks data entry. |

On Vercel these three exist as **Encrypted** variables on Production, Preview and Development;
`NEXT_PUBLIC_APP_URL` is deliberately not set there at all, because nothing in the bundle would use
it. Never re-create any of them as **Sensitive** — see rule 3 above. That mistake does not surface
as a failed build or a failed deploy; it surfaces as a live site that cannot authenticate, days
later, with every error message pointing at Google or the backend instead.

There are no server-side environment variables in the frontend: no route handlers, no server
actions, nothing reads a non-`NEXT_PUBLIC_` value.

`frontend/scripts/pw-smoke.mjs` (a Playwright smoke script, never bundled into the app) reads
`PW_BASE` (default `http://localhost:3000`), `PW_EMAIL` (default `admin@example.com`) and
`PW_PASSWORD` (no default) from the shell.

---

## Android (`android/local.properties`)

Gradle properties, not environment variables — one line, gitignored.

| Property | Required | Default | Secret | Notes |
|---|---|---|---|---|
| `apiBaseUrl` | No | `https://d2b34i3e92al6i.cloudfront.net/api/` (compiled into `BuildConfig.DEFAULT_API_BASE_URL`) | No | Note this one **does** include the trailing `/api/` — the opposite of the web variable. Emulator: `http://10.0.2.2:8000/api/`. Physical device on your LAN: `http://192.168.1.x:8000/api/`, with the backend started as `--host 0.0.0.0`. |

The Google web client ID is compiled in from `android/app/build.gradle.kts`
(`GOOGLE_WEB_CLIENT_ID`), not supplied via a property.

---

## Local infrastructure (`docker-compose.yml`)

Fixed values, listed so nothing looks mysterious. Change them only if you also change
`backend/.env`.

| Service | Setting | Value |
|---|---|---|
| postgres | `POSTGRES_USER` / `POSTGRES_PASSWORD` / `POSTGRES_DB` | `postgres` / `postgres` / `design_workshop`, published on host port **55432** |
| minio | `MINIO_ROOT_USER` / `MINIO_ROOT_PASSWORD` | `minioadmin` / `minioadmin`, API on **9000**, console on **9001** |
| create-bucket | — | one-shot job creating the public-download bucket `design-workshop` |

---

## Repository automation (GitHub Actions secrets)

Set at **Settings → Secrets and variables → Actions**. Never in a file.

| Secret | Used by | Required | Secret | Notes |
|---|---|---|---|---|
| `EC2_HOST` | `deploy-backend.yml` | Yes | No | Elastic IP of **this portal's** API box: `13.206.216.18` (instance `i-0e091ca8e6b417b52`, tagged `designrepo-api`). `15.207.145.174` is the field repository's box and must never appear here. |
| `EC2_SSH_KEY` | `deploy-backend.yml` | Yes | **Yes** | Private `.pem` contents for **this portal's** key pair `designrepo-deploy` — the file is `infra/terraform/designrepo-deploy.pem`. The sibling `fieldrepo-deploy.pem` opens the *other* product's box, and pasting it together with the *other* IP is the pair that deploys successfully onto the wrong machine. See the banner at the top of [CI.md](CI.md) before you set either of these two. |
| `BACKEND_ENV` | `deploy-backend.yml` | Yes | **Yes** | The **entire** `backend/.env` file. Piped to the box over SSH — never echoed to logs. This is where you edit `BACKEND_CORS_ORIGINS` for production. |
| `VERCEL_TOKEN` | `deploy-frontend.yml` | Yes | **Yes** | Vercel → Account Settings → Tokens. Must be scoped to the **team** owning `design-repository`, not a personal account, or the CLI 403s. The only genuinely sensitive one of the three. Absent, stage 2 skips with instructions rather than failing. |
| `VERCEL_ORG_ID` | same | Yes | No | `team_pcTf4Alb2DCIwq2IZcdu00dS`. An identifier, not a credential. Both products live in this one team, so it is the one Vercel value that is the same either way — and therefore the one that cannot warn you. |
| `VERCEL_PROJECT_ID` | same | Yes | No | `prj_uRYcc64FRwcrkvMDZg9Gp7ZEtCoc` — Vercel project **`design-repository`**, which is what `vercel link` wrote into this checkout's `frontend/.vercel/project.json`. An identifier, not a credential, but it is the **deploy target**: `prj_EzXN8hhGKpMciFBrZRdxpcgUUzN0` is the *field repository's* project, and publishing there does not fail — it succeeds, replacing another product's live site with this one's build. Never put that value in this repository. |
| `SUPABASE_KEEPALIVE_URL` / `SUPABASE_DATABASE_URL` | `keep-supabase-active.yml` → `scripts/keep-supabase-active.mjs` | **No — none of these three is set, and the workflow no longer runs on a schedule** | **Yes** | **Dormant since 2026-08-22.** A nightly ping existed because a free-tier Supabase project *pauses* when idle; the provider hosting production today suspends idle compute and wakes it on the next connection (see [The database](#the-database-what-is-required-and-where-it-runs-today)), so the ping buys this deployment nothing. That workflow's own header records a `gh secret list` finding no database URL, so the cron had been failing nightly since at least 9 August. The workflow keeps `workflow_dispatch` and the whole Supabase code path; the two `schedule:` lines are commented out. Read that file's header before changing any of this. |
| `SUPABASE_KEEPALIVE_NO_REWRITE` | same | No | No | Same dormancy. `"true"` disables the script's `:5432 → :6543` rewrite. Only needed on a provider that is not Supabase — and against such a host the rewrite is already a no-op, because it only matches the `.pooler.supabase.com` host suffix. **This is the script's own rewrite and it is still there**; the equivalent in `backend/app/core/db.py` was removed on 2026-08-22. |
| `SUPABASE_DB_SSL` | same | No | No | Same dormancy. `"false"` disables TLS for the keep-alive connection; any other value keeps SSL on. |

**The two secret inventories in this document do not agree, and this is the unresolved half.** The
`gh secret list` quoted above (in `keep-supabase-active.yml`'s header, undated) found `BACKEND_ENV`,
`EC2_HOST` and `EC2_SSH_KEY` **and nothing else**. The three `VERCEL_*` rows say Required = **Yes**
and quote two configured values. Both cannot describe the same moment, and the two readings have
very different operational meaning:

* **The inventory is current** → `VERCEL_TOKEN` is absent, so `deploy-frontend.yml`'s gate takes the
  `HAVE_VERCEL_TOKEN != true` branch and stage 2 has been **skipping with instructions on every
  push**, exactly as long as the keep-alive was failing. Deliberately a skip, not a failure, so it
  produces a green run and no alert — the failure mode this shape was chosen for.
* **The inventory is stale or partial** → the `VERCEL_*` rows stand and only the "and nothing else"
  clause is wrong.

**Nothing in this repository can tell them apart.** The one command that settles it, from a machine
authenticated to this repository: `gh secret list --repo <this repo>`. Re-run it, **date the answer
here**, and delete whichever of the two readings it disproves. Do not re-derive the answer from a
workflow's header again — that is how an undated inventory came to be quoted as present-tense
evidence in the first row of this document.

The `NEXT_PUBLIC_*` values are deliberately **not** in this table. They live in the Vercel project
and `vercel pull` fetches them into the runner at build time, so the Environment Variables screen
stays their single source of truth and nobody has to keep the same value correct in two places.

Terraform (`infra/terraform/`) additionally reads `AWS_ACCESS_KEY_ID` / `AWS_SECRET_ACCESS_KEY`
from your shell — use an IAM admin user's key pair, never root keys. `terraform.tfstate` and
`*.tfvars` are gitignored because they contain the generated media access key.

---

## Quick triage

| Symptom | Almost always |
|---|---|
| Green pipeline, live site, and **nobody can log in** — Google says `invalid_client`, password login fails too | A `NEXT_PUBLIC_*` variable in Vercel is typed **Sensitive**, so `vercel pull` never gave it to the build and Next.js inlined `undefined`. Grep the live bundle before touching Google: [DEPLOYMENT_VERCEL.md §2.2.1](DEPLOYMENT_VERCEL.md) |
| Requests go to `http://localhost:8000` from the deployed site | Same cause — `NEXT_PUBLIC_API_URL` never reached the build, so `lib/api.ts`'s local fallback got compiled in |
| Every page loads but all lists are empty and login fails with 404 | `NEXT_PUBLIC_API_URL` has a trailing `/api` or `/` |
| Requests never leave the browser; console says "Mixed Content" | `NEXT_PUBLIC_API_URL` is `http://` on an `https://` page |
| "blocked by CORS policy" but curl works | The origin is missing from `BACKEND_CORS_ORIGINS`, or the backend wasn't restarted |
| Env change in Vercel had no effect | `NEXT_PUBLIC_*` is compiled in — redeploy without the build cache. If a fresh redeploy *still* has no effect, the variable is typed Sensitive and the build never receives it |
| Backend won't start | A required variable is missing: `DATABASE_URL`, `JWT_SECRET`, `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`, `AWS_S3_BUCKET`, `MASTER_ADMIN_EMAIL` |
| `EMAXCONNSESSION` / `EMAXCONN` in the logs | `DATABASE_CONNECTION_LIMIT` raised, or more than one uvicorn worker; keep 1 web worker + the separate queue service |
| Works on Wi-Fi, fails on mobile data | An IPv4-only host slipped in — use the CloudFront and `s3.dualstack.…` hostnames |
| Google button missing, or GSI returns **403** | `NEXT_PUBLIC_GOOGLE_CLIENT_ID` blank, or the origin is not an Authorized JavaScript origin. A **401 `invalid_client`** is a different fault — see the first row |
| Transcripts stay empty | No STT key set (`ELEVENLABS_API_KEY` / `DEEPGRAM_API_KEY` / `OPENAI_API_KEY`), or the queue worker isn't running. Check the Settings hub's provider panel first: it shows which keys are actually resolvable, through the same lookup the chain uses |
| Transcripts are slow but do arrive | Working as designed. Transcription waits for the box to be idle and backs off behind a growing cooldown after a provider 429s — a throttled clip is requeued **without** spending an attempt |
| A provider key was added in the Settings hub and nothing changed | It should take effect immediately with no restart. If it did not, the value is in `.env` rather than `ManagedSecret`, and `.env` is only the fallback — but `.env` needs a restart because `get_settings()` is `@lru_cache`d (rule 2) |
| `/docs` 404s after a deploy | Correct. `BACKEND_EXPOSE_DOCS` defaults to `false`; set it `true` locally |
| A demoted user still has their old privileges | For at most `AUTH_USER_CACHE_TTL_SECONDS` (5 s), and only if the demotion happened in a *different* process — `psql`, the seed script. A demotion through the API invalidates immediately. `AUTH_USER_CACHE_ENABLED=false` removes the window entirely |
| `prisma generate` dies with `UnicodeEncodeError: 'charmap' codec can't encode characters in position …` | **Set `PYTHONUTF8=1`.** See below — this one is worth its own section, because the failure names neither the file nor the cause |
| `prisma generate` dies with `Error: spawn prisma-client-py ENOENT` | The venv's `Scripts` directory is not on `PATH`. The Node CLI spawns the Python generator as a sibling executable, so `python -m prisma` alone is not enough: prepend `backend/.venv/Scripts` to `PATH` for the call |
| A route 500s with `Field does not exist in enclosing type` or `db.<model>` raises `AttributeError` | The generated client is behind `schema.prisma`. Regenerate — and if that fails, it is one of the two rows above |

---

## Regenerating the Prisma client on Windows

**`PYTHONUTF8=1` IS REQUIRED, AND WITHOUT IT REGENERATION IS NOT MERELY AWKWARD — IT IS IMPOSSIBLE.**

```bash
cd backend
PATH="$PWD/.venv/Scripts:$PATH" PYTHONUTF8=1 .venv/Scripts/python.exe -m prisma generate --schema prisma/schema.prisma
```

`prisma/generator/generator.py` writes the packaged schema with `pathlib.write_text()` and no
`encoding=`, so it encodes at the **locale default** — cp1252 on a standard Windows install.
`prisma/schema.prisma` contains `─` (U+2500) and `▶` (U+25B6) in its comment banners, and cp1252 can
encode neither. Every run dies with a character offset and no mention of the schema, the encoding or
the characters involved.

**Why this is written down rather than left to be rediscovered.** It cost this repository a
**CRITICAL** finding. The client froze at its last successful generation while `schema.prisma` went
on being edited; four models and three columns drifted out of the client; eleven endpoints were dead
on the wire and the AI-verb daily cap could not count at all — and none of it was visible, because a
suite of 2000+ tests that never drives those endpoints against a database stayed green throughout.
A build step that fails only on some machines, for a reason its error message does not name, drifts
silently until something downstream is already broken.

Two things that follow:

* **Do not "fix" the schema by removing the box-drawing characters.** They are in comments that
  explain the model, the next non-ASCII character to arrive would reintroduce this, and the actual
  defect is the missing `encoding=` upstream. `PYTHONUTF8=1` addresses the cause for every character,
  not just the two that happen to be there today.
* **Regenerate with no test run in flight.** It rewrites files inside `site-packages/prisma/` that a
  running `pytest` is importing, which produces an inexplicable red build in somebody else's lane.

**Check it worked** rather than trusting the exit code — compare the schema's models against the
client's classes; they must be equal in number and name:

```bash
python -c "import re; s=set(re.findall(r'^model (\w+)', open('prisma/schema.prisma',encoding='utf-8').read(), re.M)); c=set(re.findall(r'^class (\w+)\(', open('.venv/Lib/site-packages/prisma/models.py',encoding='utf-8').read(), re.M)); print(len(s), len(c), sorted(s-c))"
```

---

## How this document is kept true

There is exactly one source for the backend list: **`backend/app/core/config.py`**. Everything else in
this file is prose about those names. So the maintenance procedure is a set difference, and it takes
one command:

```bash
# Every variable config.py reads. Diff this against the tables above — anything in the output
# that is not in this document is undocumented, and anything documented that is not in the
# output has been deleted from the code.
grep -oP 'alias="\K[A-Z_0-9]+' backend/app/core/config.py | sort
```

| Claim class | Kept true by |
|---|---|
| The backend variable list is complete | The command above. Run it after any change to `config.py`. |
| The frontend variable list is complete | The `grep` in the Frontend section, which is dated. |
| Defaults | The `Field(default=…)` in `config.py`. **One default is a lie by design** and is called out in a blockquote: `ELEVENLABS_STT_MODEL`. If another such case appears, it needs the same treatment — a table cell cannot express "the config default is not what runs". |
| `SCALE_*` and the AI-feature keys | Owned elsewhere: `backend/app/scale/README.md` and [AI_FEATURES.md](AI_FEATURES.md). This file lists the names only, so it stays a complete index without becoming a second source of truth. |
| GitHub Actions secrets | `.github/workflows/*.yml`. `grep -ho 'secrets\.[A-Z_]*' .github/workflows/*.yml \| sort -u` is the equivalent set difference. |
| `VERCEL_PROJECT_ID` / `VERCEL_ORG_ID` | `checkVercelIds` in `docs/tools/check-docs.mjs`, which ties this table's two rows to [CI.md](CI.md) §2 and to `.github/workflows/deploy-frontend.yml`'s header, and confirms them against `frontend/.vercel/project.json` when a checkout has one. Added 2026-08-22 because this table survived the wave that corrected the other three, still handing the reader the *field repository's* project id and calling it harmless. Any *other* `prj_`/`team_` id — a typo, or one copied out of an old dashboard link — is caught by `checkSiblingIdentity`'s shape rule, which runs over every tracked file rather than these three. |
| §4's infrastructure table | `checkSecondRegister` in `docs/tools/check-docs.mjs` compares it row by row with [CI.md](CI.md) §0's register, which is the one place these facts are meant to be established. Rows only this table carries (Google OAuth, the systemd units) are declared there; a new row that is in neither list fails, and so does a filled-in CloudFront cell while the question in §4 is still open. Added 2026-08-22, when the two tables were already disagreeing about CloudFront and inverting this one's S3 row produced no finding at all. |
| Vercel variables | The Vercel dashboard — **UNVERIFIED from this repository**. `vercel env ls production` is the check, and rule 3 (Encrypted, never Sensitive) is the thing it exists to catch. |

**Review triggers:** `backend/app/core/config.py`, `backend/.env.example`, any new `process.env.`
reference under `frontend/`, or a new workflow secret.

**Known unverified:** every value stated as a *production* value — the bucket name, the region, the
CloudFront hostname, the Vercel org and project ids — is recorded from a deployment, not read from
code. They are correct as of 2026-07-27 to the extent the deployment has not changed under them.

# Deploying the web frontend to Vercel

This is the complete runbook for putting `frontend/` (Next.js 16) on Vercel in front of the live
FastAPI backend. It assumes nothing beyond a GitHub account with access to this repository and a
Vercel account. Sister documents:

- [docs/ENVIRONMENT.md](ENVIRONMENT.md) — every environment variable, per service, with defaults.
- [backend/DEPLOY_AWS.md](../backend/DEPLOY_AWS.md) — the EC2/S3/CloudFront side.
- [docs/QA_AUDIT.md](QA_AUDIT.md) — historic failure modes and their resolutions.

---

## 0. What is being deployed, and what is not

| Piece | Where it runs | Notes |
|---|---|---|
| Next.js web app | **Vercel** | Deployed by `.github/workflows/deploy-frontend.yml`, **after** the backend deploy succeeds ([docs/CI.md](CI.md)) — not by Vercel's Git integration (§3). Every page under a dynamic segment (`[id]`, `[artisanId]`, …) renders on demand in a function pinned to `bom1` (Mumbai); every other page prerenders to static HTML (§8). No route handlers, no server actions, no `fs` access — see §8. |
| FastAPI API | **AWS EC2** `t3.small`, behind nginx | Not deployed by Vercel. Auto-deployed by `.github/workflows/deploy-backend.yml`. A `t3.micro` until 2026-09-17; [CI.md](CI.md) §1.2 has the measurement that retired it. |
| HTTPS + IPv6 edge for the API | **CloudFront** `https://d3ekigkotd1xa2.cloudfront.net` | The value the browser actually talks to. |
| Database | **PostgreSQL**, managed | Reached only by the backend. Which provider hosts it is deliberately not restated here — it is recorded once, with its evidence, under "The database" in [ENVIRONMENT.md](ENVIRONMENT.md). |
| Media | **S3** `designrepo-media-626159998512` (dual-stack endpoints) | Browsers upload straight to S3 with signed PUT URLs — the bytes never pass through Vercel. |

The browser calls the backend **directly**; there is no Next.js proxy or rewrite in front of it.
That is deliberate: no API traffic and no media bytes pass through a Vercel Function, so the
deployment stays off Vercel's bandwidth and function-invocation budget. It also means **CORS on the
backend is not optional** (§4).

---

## 1. Import the repository

1. Sign in to <https://vercel.com> with the GitHub account that can read this repository.
2. **Add New… → Project**, then **Import** `designer-portal`. (This said `documentation-portal` until
   2026-10-09. That is the field repository's repository, and importing it publishes the other
   product.)
3. On the configure screen, set:

   | Setting | Value | Why |
   |---|---|---|
   | **Root Directory** | `frontend` | This is a monorepo. Leave it at the repo root and the build fails immediately — there is no `package.json` with Next.js there. Click **Edit** next to Root Directory and pick `frontend`. |
   | **Framework Preset** | Next.js | Auto-detected from `frontend/vercel.json` (`"framework": "nextjs"`). |
   | **Build Command** | `next build` | From `frontend/vercel.json`. Leave the override off. |
   | **Install Command** | `npm ci` | From `frontend/vercel.json`; installs exactly what `frontend/package-lock.json` pins. If the build fails with `npm ci can only install packages when your package.json and package-lock.json are in sync`, run `npm install` locally and commit the updated lockfile. |
   | **Output Directory** | `.next` | Default for Next.js. |
   | **Node.js Version** | `24.x` | The newest major Vercel offers (24.x, 22.x and 20.x on 2026-10-09). **`engines.node` in `frontend/package.json` overrides this setting**, and it reads `24.x`: Vercel runs the site's on-demand pages on the `engines.node` major whatever this dropdown says, `vercel build` stamps that major on every function, and `checks.yml`'s web job, `e2e-live.yml` and `deploy-frontend.yml` install Node from the same field, so CI, the build and production are one major by construction. Keep this setting at that major anyway, so nobody reading the dashboard is misled and nothing changes if the field is ever removed; the deploy warns on every run where the two differ. **Raise, never lower.** Until 2026-10-09 this row said `22.x` to match a CI runner that was behind production, and following it moved production down a major; the fix was to raise CI. When Vercel lists a newer major, raise `engines.node` (with `@types/node` and `frontend/Dockerfile`'s `NODE_VERSION`), then this setting. |
   | **Function Region** | `bom1` (Mumbai) | Pinned by `"regions"` in `frontend/vercel.json`, which the CLI sends with every deployment, so the dashboard value is overridden on each publish. The people using the app, the API box and the production database are all in or near Mumbai. Measured 2026-10-09 with the project default `iad1` (Washington, D.C.): an on-demand page took 0.45–0.57 s to first byte warm and 1.14 s cold, against 0.26 s for a static one. |

4. **Do not deploy yet** — add the environment variables first (§2). A first build without
   `NEXT_PUBLIC_API_URL` succeeds but silently ships a bundle pointing at `http://localhost:8000`.

---

## 2. Environment variables

Set these under **Project → Settings → Environment Variables**. Names are case-sensitive and must
match exactly, and every one of them must be created with type **Encrypted** — read §2.2 before you
add the first one, because the wrong type here fails silently and takes the whole site down.

| Name | Value (production) | Required | Type | Environments |
|---|---|---|---|---|
| `NEXT_PUBLIC_API_URL` | `https://d3ekigkotd1xa2.cloudfront.net` | **Yes** | Encrypted | Production, Preview, Development |
| `NEXT_PUBLIC_GOOGLE_CLIENT_ID` | the Google **web** OAuth client ID | Only for Google sign-in | Encrypted | Production, Preview, Development |
| `NEXT_PUBLIC_MAPTILER_API_KEY` | MapTiler key | No | Encrypted | Production, Preview, Development |
| `NEXT_PUBLIC_APP_URL` | `https://<your-project>.vercel.app` (or the custom domain) | No — see below | Encrypted | Production, Preview, Development |

~~Those first three are what the project actually holds today; `NEXT_PUBLIC_APP_URL` is not set there
at all.~~ **That was not what the project held. Measured 2026-10-09 through the Vercel API**, the
`designer-repository` project held:

| What | Production | Preview | Development |
|---|---|---|---|
| `NEXT_PUBLIC_API_URL`, `NEXT_PUBLIC_GOOGLE_CLIENT_ID`, `NEXT_PUBLIC_MAPTILER_API_KEY` | Encrypted | **Sensitive** | Encrypted |
| `NEXT_PUBLIC_APP_URL` | **Sensitive** | **Sensitive** | Encrypted |
| `DATABASE_URL`, `PGPASSWORD`, `POSTGRES_*`, `NEON_*` and the rest of a database set | present | present | present |

None of it broke production. Production's three values are Encrypted, so `vercel pull` hands them
over, and the pipeline builds production only. `NEXT_PUBLIC_APP_URL` is inert on this side, and the
deploy's pull assertion exempts it for that reason. The Preview copies would break a preview built
the way this pipeline builds (§2.2), and no previews are built. The last row is §2.3's rule broken
outright. **The agreed fix (2026-10-09) is dashboard work, recorded here when it is done:** re-create
the Preview copies as Encrypted, or drop the Preview scope; delete the `NEXT_PUBLIC_APP_URL` entries;
disconnect the database store (§2.3). The live production alias is
<https://designer-repository.vercel.app>. §3.1 explains why that is an alias and not the project's
domain.

### 2.1 The trailing `/api` trap — read this before anything else

`frontend/lib/api.ts` reads the variable into `API_BASE` (a blank value counts as missing) and every
request is built as:

```ts
const configuredBase = (process.env.NEXT_PUBLIC_API_URL ?? "").trim();
export const API_BASE = configuredBase || LOCAL_DEV_BASE; // "http://localhost:8000"
…
fetch(`${API_BASE}/api${path}`, …)
```

The client appends `/api` **itself**. So `NEXT_PUBLIC_API_URL` must be the **origin only**:

| Value you set | Request that goes out | Result |
|---|---|---|
| `https://d3ekigkotd1xa2.cloudfront.net` | `https://d3ekigkotd1xa2.cloudfront.net/api/artisans` | ✅ correct |
| `https://d3ekigkotd1xa2.cloudfront.net/api` | `https://…/api/api/artisans` | ❌ every screen 404s |
| `https://d3ekigkotd1xa2.cloudfront.net/` | `https://…//api/artisans` | ❌ every screen 404s |
| `http://15.207.145.174` | blocked before it leaves the browser | ❌ mixed content (§7.1) — and that IP is the **field repository's** box, per [ENVIRONMENT.md](ENVIRONMENT.md) §4; this portal's is `13.206.216.18` |

This has broken the deployment more than once. If the app loads but every list is empty and login
fails, open devtools → Network and look for a doubled `/api/api/` segment before debugging anything
else. If instead the requests are aimed at `http://localhost:8000`, the variable is not merely
malformed — it never reached the build at all, which is §2.2.

`NEXT_PUBLIC_APP_URL` is listed for completeness only: **no code under `frontend/` reads it**
(`grep -rn "process.env" frontend/` returns `NEXT_PUBLIC_API_URL`, `NEXT_PUBLIC_GOOGLE_CLIENT_ID`
and `NEXT_PUBLIC_MAPTILER_API_KEY`). It is the **backend's** variable of the same name
(`app/core/config.py`), reused here so one value stays in sync. Setting it in Vercel is harmless
and keeps the two aligned, but leaving it out breaks nothing. What actually has to know the Vercel
origin is the API box (§4.1).

**The backend's copy is a different matter: it is REQUIRED in production** (2026-10-09). It is the
origin every set-password, invite and sanction link is built on, and its default is
`http://localhost:3000`, so a wrong or missing value issues links that open on a laptop or on an
origin CORS refuses. `.github/workflows/deploy-backend.yml` therefore pins it to
`https://designer-repository.vercel.app` on every deploy, in the same step as the CORS origins, and
fails the deploy if the line does not land. [ENVIRONMENT.md](ENVIRONMENT.md) carries the row.

### 2.2 The variable **type** trap — `Sensitive` ships an empty build

Vercel asks for a *type* when you create a variable: **Encrypted** (the default) or **Sensitive**.
The difference is not how well the value is guarded at rest — both are encrypted — but whether it
can ever be read back. **Sensitive is write-only.** Once saved, neither the dashboard, nor the REST
API, nor `vercel pull` will return that value to anybody, ever; the only way to "see" it again is to
delete it and type a new one. For a value that nothing but Vercel's own build container needs, that
is a real improvement.

It is precisely the wrong setting here, for two independent reasons.

The first is mechanical: **this pipeline does not build on Vercel.**
`.github/workflows/deploy-frontend.yml` runs `vercel pull` on a GitHub runner to fetch the project's
settings and environment, runs `vercel build` there, and then uploads the finished output with
`vercel deploy --prebuilt` (§3). A sensitive variable is exactly the thing `vercel pull` declines to
hand over, so the runner compiles with that name absent from its environment altogether. Next.js
does not treat that as an error. `process.env.NEXT_PUBLIC_GOOGLE_CLIENT_ID` is a compile-time
textual substitution, and with nothing to substitute it inlines the literal `undefined` and carries
on. No warning is printed. `next build` succeeds, `vercel deploy` succeeds, the workflow's smoke
check gets its `200` because the landing page renders perfectly well without an API, and all three
CI stages report green over a site on which nobody can log in. On 2026-07-26 that is what happened
here, to all three variables at once.

The second reason is that the protection was imaginary anyway. Every `NEXT_PUBLIC_*` value is
compiled into JavaScript served to every visitor (§2.3): the API origin and the Google client ID are
sitting in the bundle, readable in devtools, **by design**. Marking one Sensitive hides it from the
handful of people maintaining the deployment while continuing to publish it to the entire internet.
It protects nothing whatsoever, and it costs you the build.

So, plainly: **`NEXT_PUBLIC_*` must be type Encrypted, never Sensitive.** If a value genuinely has
to stay secret, the answer is never a `NEXT_PUBLIC_` variable with a stricter type — it is to keep
the value out of the frontend project entirely and let the backend hold it
([ENVIRONMENT.md](ENVIRONMENT.md)).

A variable's type cannot be edited in place. Repairing one means deleting it and re-creating it with
the same name and value as Encrypted, ticked for all three environments — and then redeploying (§5),
because the bad bundle is already published and keeps serving `undefined` until something rebuilds.

The pipeline no longer lets this through. `deploy-frontend.yml` now asserts twice: once on the
*input*, that `vercel pull` wrote every `NEXT_PUBLIC_*` it returned into
`.vercel/.env.production.local` as a real value — not empty, not `undefined`, and not the
`[SENSITIVE]` placeholder the CLI writes for a withheld one — with a plausible shape for the two
whose shape is knowable, `NEXT_PUBLIC_API_URL` and `NEXT_PUBLIC_GOOGLE_CLIENT_ID`; and once on the
*artifact*, that those two values are genuinely present somewhere in `.vercel/output` before anything
is published. `NEXT_PUBLIC_APP_URL` is exempt from the first, because nothing in the bundle reads it. The second
check exists because a value can pull correctly and still fail to be inlined — a wrong Root
Directory, or `process.env` accessed in a form Next.js cannot statically substitute — which is a
different bug wearing the same symptom. Both fail the run with the fix in the error message, and
neither ever prints a value. Details in [docs/CI.md](CI.md).

Auditing the current state costs one command, and it prints types rather than values, so it is safe
to paste anywhere:

```console
$ cd frontend && vercel env ls production
 name                               value               environments                        created
 NEXT_PUBLIC_MAPTILER_API_KEY       Encrypted           Production, Preview, Development    4m ago
 NEXT_PUBLIC_GOOGLE_CLIENT_ID       Encrypted           Production, Preview, Development    4m ago
 NEXT_PUBLIC_API_URL                Encrypted           Production, Preview, Development    4m ago
```

Anything reading `Sensitive` in that column is a broken deployment waiting for its next build. That
listing is the target state, not a transcript of this project: §2's measured inventory is what it
actually held on 2026-10-09.

#### 2.2.1 Diagnosing it, when the symptom names the wrong culprit

This failure is nasty because every signal points somewhere else.

Google answers the sign-in attempt with **`Error 401: invalid_client — The OAuth client was not
found`**. That message is literally true and completely misleading: the client ID inlined as
`undefined` reaches Google as an empty string, Google looks up the client named `""`, and reports
that no such client exists. It reads as though somebody deleted the OAuth client, which sends you
into the Google Cloud console to audit credentials nobody has touched.

Email/password login fails at the same moment, which looks like a second, unrelated outage and
tempts you into the backend. It is the same cause: with `NEXT_PUBLIC_API_URL` missing, the
`LOCAL_DEV_BASE` fallback in `frontend/lib/api.ts` takes over and the bundle aims every request at
`http://localhost:8000` — the developer's own machine — which an HTTPS page is not even allowed to
send (§7.1).

One command settles it. Fetch the live JavaScript and grep it for the two values that must be in
there; both are public by definition, so nothing here leaks:

```bash
curl -s https://designer-repository.vercel.app/login \
  | grep -o '/_next/static/chunks/[^"]*\.js' | sort -u \
  | while read -r c; do curl -s "https://designer-repository.vercel.app$c"; done \
  | grep -o -e 'https://[a-z0-9]*\.cloudfront\.net' \
            -e '[0-9]\{8,\}-[a-z0-9]*\.apps\.googleusercontent\.com' \
  | sort -u
```

A healthy production bundle prints both:

```
614092441670-3e5k15srupq9mfpg3aktqfkjvkavu0g3.apps.googleusercontent.com
https://d3ekigkotd1xa2.cloudfront.net
```

**Silence is the diagnosis.** If those strings are not in the shipped JavaScript then no browser can
be sending them, so the fault is in the build — not in Google, not in the OAuth client, not in CORS,
and not in the backend. Go and look at the variable types (above) before you touch anything else.
Swap in a preview hostname to run the same check against a preview deployment.

### 2.3 Public means public

Every variable is prefixed `NEXT_PUBLIC_`, which means **Next.js inlines the literal value into the
JavaScript bundle at build time**. Two consequences:

1. None of them can hold a secret. Never add `JWT_SECRET`, `DATABASE_URL`, an AWS key, or a Google
   client *secret* to the Vercel frontend project — those belong to the backend only.

   **Measured 2026-10-09, this project broke that rule without anybody typing a variable.** A
   Vercel Storage store from Neon (`neon-aero-drum`), the provider that hosted this deployment's
   database until 2026-09-02, was still connected to `designer-repository`. A connected store
   injects its variables into every environment: `DATABASE_URL`, `PGPASSWORD`, `POSTGRES_PASSWORD`
   and the rest. Nothing under `frontend/` reads them and Next.js inlines only `NEXT_PUBLIC_*`, so no
   bundle carries them. But every deploy's `vercel pull` writes them onto a GitHub runner, and Vercel
   hands them to the functions that render on-demand pages, where a future dependency that looks
   for `DATABASE_URL` would quietly find a second database. The deploy now names any such variable
   in a warning on every run (names, never values).

   The leftovers are being removed by **disconnecting** the store from this project: Vercel →
   Storage → the store → Projects → Disconnect. That removes all of its variables at once. Two
   things must not be done instead. Do not uninstall the team's database integration, which also
   serves another team project. Do not delete the store itself: whether it is the frozen fallback
   [ENVIRONMENT.md](ENVIRONMENT.md) records under "Previously" is not established, so deleting it
   waits for its owner's decision and a `pg_dump`. After the disconnect, force one publish
   (§5) so the live functions lose the variables too, then make the deploy's warning step fail
   instead of warn (it says how).
2. **Changing a value in the dashboard does nothing until you redeploy.** The old value is already
   compiled into the shipped bundle. See §5.

### 2.4 Preview vs Production environments

Vercel builds three scopes and you tick which ones each variable applies to:

- **Production** — the `main` branch → your production domain.
- **Preview** — every other branch and every pull request → a throwaway
  `https://<project>-<hash>-<team>.vercel.app` URL.
- **Development** — `vercel dev` on a laptop. Local work normally uses `frontend/.env.local`
  (copy `frontend/.env.local.example`) and ignores this scope entirely.

Ticking all three for every variable above is the simplest correct setup and is what this project
does. Two caveats if you later split them:

- Preview deployments get a **new hostname per deployment**. Those hostnames are not in
  `BACKEND_CORS_ORIGINS`, so previews cannot call the production API unless you add them (§4) or
  point previews at a separate staging API.
- `NEXT_PUBLIC_APP_URL` cannot be correct for previews in the general case — set it to the
  production domain and accept that previews report the production origin, or leave it out of the
  Preview scope.

---

## 3. Deploy

> **Production deploys are now done by GitHub Actions, not by Vercel's Git integration.**
> `.github/workflows/deploy-frontend.yml` runs `vercel pull` → `vercel build --prod` →
> `vercel deploy --prebuilt --prod`, and it is chained to run **only after the backend deploy
> succeeds**. That ordering is the whole point: the browser calls the FastAPI box directly (§0), so
> a frontend published ahead of its backend spends the gap calling endpoints that answer 404 — and
> the backend deploy stops the API service to run migrations, so the gap is real. Full pipeline,
> secrets and troubleshooting: **[docs/CI.md](CI.md)**.
>
> This also means Vercel's own auto-deploy for `main` must stay switched off, or it races ahead of
> the backend and re-creates exactly the bug the pipeline prevents.

> **Updated 2026-07-27 — the existing project is no longer linked to GitHub at all.** Cancelling
> Git-triggered builds was not enough: a cancelled build is still a deployment record and still an
> email, for work nobody wanted, and twelve of them failed outright with `No Next.js version
> detected` before the Root Directory was set. So the link was removed
> (`DELETE /v9/projects/{id}/link`). GitHub Actions authenticates with `VERCEL_TOKEN` instead, and
> `vercel deploy --prebuilt` does not need the project to know about GitHub. **The cost is that pull
> requests no longer get automatic preview deployments** — which contradicts the "leave previews on"
> advice above and is the deliberate trade. Re-link in the dashboard if previews are wanted back, and
> rely on the layers in step 2 to keep Git builds off `main`.

1. Click **Deploy** for the first build (1–3 minutes) so the project exists and the domain is
   assigned.
2. Confirm dashboard-triggered production builds are off. ~~For the existing project this is settled
   three ways over~~ — **two ways, measured 2026-10-09**: the Git link is **removed**, and
   `frontend/vercel.json` carries `"ignoreCommand": "exit 0"`. The third layer this step claimed,
   `gitProviderOptions.createDeployments` disabled at the project level, read `enabled` on
   `designer-repository` that day (the field repository's project had it disabled). Disabling it is
   a dashboard or API change: `scripts/vercel-ci-setup.mjs` step 2 makes it, or `PATCH
   /v9/projects/{id}` with `{"gitProviderOptions":{"createDeployments":"disabled"}}`. Until then,
   re-linking Git for previews would leave `ignoreCommand` as the only layer. The Ignored Build Step
   is a Git-integration feature only, so it can never block `vercel build` / `vercel deploy
   --prebuilt` from CI. If you are standing up a *new* project, set the Ignored Build Step under
   **Settings → Git → Ignored Build Step** before the first Git build.
3. Add the `VERCEL_TOKEN`, `VERCEL_ORG_ID` and `VERCEL_PROJECT_ID` repository secrets — see
   [docs/CI.md §2](CI.md#2-required-repository-secrets) for exactly where each value comes from.
4. Before trusting the first CI deploy, run `vercel env ls production` and check the type column
   reads `Encrypted` for every `NEXT_PUBLIC_*` (§2.2). ~~Nothing downstream will tell you if it does
   not~~ — **the pipeline now does.** Stage 2 asserts three times over that the values were pulled,
   that they reached the compiled bundle, and that the bundle the CDN serves is the one that was
   verified ([CI.md §1](CI.md)). Run the check anyway: a red run at deploy time is better than a
   broken site, and a correct dashboard is better than a red run.

From then on, a push to `main` deploys the backend first and this project second, automatically.

Verify the deployment. Note that step 1 is deliberately first: the landing page rendering proves
almost nothing, because it renders just as happily from a bundle with no API URL in it.

1. Grep the shipped JavaScript for the API origin and the Google client ID (§2.2.1). One command, no
   browser, and it is the only check that catches a build compiled without its environment.
2. Open the deployment URL — the landing page should render.
3. Go to `/login`, open devtools → Network, and attempt a login.
4. The request must be `POST https://d3ekigkotd1xa2.cloudfront.net/api/auth/login` — HTTPS, single
   `/api`, and a `200` (or a `401` for wrong credentials, which still proves connectivity).

### 3.1 The production alias, the project's domain, and rolling back

**Measured 2026-10-09 through the Vercel API.** `designer-repository.vercel.app`, the address users,
`monitor.yml`, the CORS pin and every password link use, was **not one of the project's domains**.
It is an alias somebody set by hand on 2026-08-16. The project's only domain is
`design-repository-kappa.vercel.app`, the one Vercel generated. That difference decides what moves
on a deploy and what moves on a rollback:

| | moves `design-repository-kappa.vercel.app` | moves `designer-repository.vercel.app` |
|---|---|---|
| `vercel deploy --prod` (the pipeline's upload) | yes, **except after a rollback** (below) | **no** |
| the pipeline's `vercel alias set` step | — | **yes**, on every publish |
| Instant Rollback, `vercel rollback`, `vercel promote` | yes | **no** |
| `vercel alias set <deployment-url> designer-repository.vercel.app` | — | **yes** |

Kappa serves the current build, because `--prod` moves it, but the API refuses its origin
(`deploy-backend.yml` pins CORS to the alias only), so anybody who reaches the site through the
dashboard's Visit link sees "Failed to fetch" on sign-in.

**The agreed fix (2026-10-09) is a dashboard change, recorded here when it is made:** add
`designer-repository.vercel.app` under **Project → Settings → Domains**, and set
`design-repository-kappa.vercel.app` to a 308 redirect to it. After that, `--prod` and every kind of
rollback move the address users visit, and kappa never serves a page of its own. Make the change
before turning on Deployment Protection, or the live address could end up behind a Vercel login.

**The workflow's `vercel alias set` step stays either way, and it is not a no-op after the change.**
This paragraph said it would be until 2026-10-09, which was wrong. An Instant Rollback, from the
dashboard or as `vercel rollback`, turns **off** auto-assignment of production domains
([Vercel: Undo a rollback](https://vercel.com/docs/instant-rollback#undo-a-rollback)). From then on
`vercel deploy --prod` moves no project domain at all: every new deployment is staged, the CLI still
exits 0, and the project stays on the rolled-back build until a deployment is promoted
(`vercel promote <url>`, or **Undo Rollback** on the production tile, which also turns
auto-assignment back on). Through that state the alias-set step is the only thing that keeps each
publish live, so it must never be removed on the grounds that the alias became a project domain.
Until the change it is also the only thing that moves the alias at all.

**Rolling the web app back**, which works in both states:

```bash
vercel ls designer-repository --prod                 # find the last deployment known to work
vercel alias set <that-deployment-url> designer-repository.vercel.app
```

Then make the next push to `main` the fix or a revert. Stage 2's gate compares `main` with what the
alias serves ([CI.md](CI.md) §1), so any other push sees `frontend/` differing from production and
publishes the broken tree again. That is the gate working as designed: it publishes what `main` holds.

---

## 4. Backend changes required for the Vercel origin

The API rejects browser origins it does not know. Two allowlists must contain the Vercel origin.

### 4.1 API CORS

`backend/app/core/config.py` reads `BACKEND_CORS_ORIGINS` — a comma-separated list of exact
origins (scheme + host, **no trailing slash, no path, no wildcards**) that FastAPI's
`CORSMiddleware` accepts:

```dotenv
BACKEND_CORS_ORIGINS=https://your-project.vercel.app,https://repo.example.org,http://localhost:3000
```

~~To apply it on the live box, edit the `BACKEND_ENV` GitHub Actions secret, which holds the whole
`backend/.env` file, then re-run `.github/workflows/deploy-backend.yml`.~~ **Corrected 2026-10-09:
production's value does not come from that secret.** The deploy's step *Pin the CORS origins and the
link origin* deletes any `BACKEND_CORS_ORIGINS` line the secret carries and writes
`https://designer-repository.vercel.app,http://localhost:3000` in its place, on every deploy, beside
the backend's `NEXT_PUBLIC_APP_URL` (§2.1). So to change production's origins, change that step in
`.github/workflows/deploy-backend.yml` and push. The workflow writes the `.env` into the new release
and restarts `fieldrepo` and `fieldrepo-queue`. Editing the secret changes nothing the box reads for
these two keys.

To test a value by hand first, open a shell on the box (there is no standing SSH access), edit the
live release's `.env`, and restart:

```bash
aws ssm start-session --target i-0e091ca8e6b417b52
sudo -u ubuntu nano /home/ubuntu/app/current/backend/.env
sudo systemctl restart fieldrepo fieldrepo-queue
curl -fsS http://127.0.0.1:8000/health/ready
```

**`current` is a symlink** to the live release, so an edit made this way lives in that release only
and **is lost the next time a deploy flips the symlink**: the next release's `.env` is rebuilt from
the `BACKEND_ENV` secret plus the pinned values. Use it to test, then put the value where the deploy
reads it. Rolling the backend back is a different operation, the symlink flip in
[CI.md](CI.md) §1.2.

### 4.2 S3 bucket CORS

The browser PUTs media straight to S3, so the bucket's CORS rule needs the Vercel origin too.
Bucket → **Permissions → CORS**:

```json
[{
  "AllowedHeaders": ["*"],
  "AllowedMethods": ["PUT", "GET", "HEAD"],
  "AllowedOrigins": ["https://your-project.vercel.app"],
  "ExposeHeaders": ["ETag"]
}]
```

In Terraform this is the `cors_allowed_origins` variable (`infra/terraform/`).

### 4.3 Google OAuth authorised origins

Add the Vercel origin to the Google Cloud console → **APIs & Services → Credentials → your web
OAuth client → Authorized JavaScript origins**. Without it, Google Identity Services returns `403`
and the sign-in button never renders. Add both `https://your-project.vercel.app` and any custom
domain.

---

## 5. Redeploy after an environment change

Because `NEXT_PUBLIC_*` values are compiled in, editing them in the dashboard has **no effect on the
live site** until a new build runs:

**Preferred, now that CI owns production:** GitHub → Actions → *Deploy frontend to Vercel* →
**Run workflow** (leave `force` = true). It runs `vercel pull` first, so it always picks up the
current dashboard values — with the one exception that `vercel pull` cannot fetch a value typed
Sensitive, and re-running the workflow over one of those just republishes the same empty bundle
(§2.2). Then hard-reload the site (Ctrl-F5) to drop the cached bundle.

From the Vercel dashboard instead — note this only works if you have not disabled dashboard builds
per §3, and it bypasses the backend-first ordering:

1. **Deployments** tab → the most recent production deployment → **⋯ → Redeploy**.
2. **Untick "Use existing Build Cache"** so the variables are read fresh.
3. Wait for the build, then hard-reload the site (Ctrl-F5) to drop the cached bundle.

Pushing an empty commit (`git commit --allow-empty -m "chore: redeploy"`) goes through the full
pipeline but publishes nothing: stage 2's gate finds production already serving this `frontend/`
tree and skips. Use **Run workflow** rather than an empty commit.

---

## 6. Custom domain

1. **Project → Settings → Domains → Add**, enter e.g. `repo.example.org`.
2. Add the DNS record Vercel shows at your registrar (`CNAME` → `cname.vercel-dns.com` for a
   subdomain; `A` → `76.76.21.21` for an apex domain).
3. Wait for the certificate to be issued (usually minutes; Vercel provisions Let's Encrypt).
4. Then, in this order:
   - in `.github/workflows/deploy-backend.yml`'s pin step, add the domain to `BACKEND_CORS_ORIGINS`
     and make it the backend's `NEXT_PUBLIC_APP_URL` (§4.1), then let the backend deploy;
   - add it to the S3 bucket CORS (§4.2);
   - add it to the Google OAuth authorised origins (§4.3);
   - optionally set the frontend's `NEXT_PUBLIC_APP_URL` to match. Nothing reads it (§2.1).

Skipping any of the first three leaves the site loading but unable to log in or upload, and leaving
out the link origin sends every password link to the old address.

---

## 7. Troubleshooting

### 7.1 Mixed content — "blocked loading mixed active content"

An HTTPS page may not call an HTTP endpoint; the browser blocks the request before it is sent, so
the server logs show nothing at all. This is why `NEXT_PUBLIC_API_URL` must point at CloudFront
(`https://d3ekigkotd1xa2.cloudfront.net`) and never at the raw EC2 origin
(`http://15.207.145.174` — the **field repository's** box, per [ENVIRONMENT.md](ENVIRONMENT.md) §4,
which is also where the unresolved question about the distribution in front of it is recorded).
Symptom: every request fails instantly, devtools console shows
`Mixed Content: The page at 'https://…' was loaded over HTTPS, but requested an insecure resource`.

### 7.2 Everything 404s / lists are empty

`NEXT_PUBLIC_API_URL` includes `/api` or a trailing slash. See §2.1. Check the Network tab for
`/api/api/` or `//api/`. Fix the variable **and redeploy** (§5) — editing it alone changes nothing.

### 7.3 CORS preflight failure

Console reads `Access to fetch at 'https://…/api/…' from origin 'https://…vercel.app' has been
blocked by CORS policy: Response to preflight request doesn't pass access control check`.

Checklist:
- The exact origin (scheme + host, no trailing slash) is in `BACKEND_CORS_ORIGINS`.
- The backend was **restarted** after the change — the settings object is `@lru_cache`d, so a
  running process never picks up an edited `.env`.
- You are testing the origin you allowlisted. Preview deployments have per-deployment hostnames
  that are *not* covered by the production entry (§2.4).
- The same request from curl works — that confirms it is CORS and not an API error, because curl
  sends no `Origin` header and therefore never triggers the check.
- For S3 upload failures specifically, it is the **bucket's** CORS rule, not the API's (§4.2).

### 7.4 API unreachable from a phone on mobile data, fine on Wi-Fi

Indian mobile networks (Jio/Airtel) are increasingly **IPv6-only**. The EC2 origin is IPv4-only and
has no `AAAA` record, so the request never resolves. CloudFront is dual-stack, which is the reason
the API is fronted by it; S3 media likewise uses the dual-stack endpoint
(`…s3.dualstack.ap-south-1.amazonaws.com`), minted by
`backend/app/services/s3.py::public_url_for_key`. If you ever hardcode a plain
`…s3.ap-south-1.amazonaws.com` URL, media will silently fail to load on those networks.

### 7.5 Build fails with `npm ci can only install packages when … in sync`

`frontend/package-lock.json` is stale relative to `frontend/package.json`. Run `npm install` in
`frontend/`, commit the updated lockfile, and push.

### 7.6 Build fails because the Root Directory is wrong

`No Next.js version detected` or `Couldn't find any pages or app directory` means the Root
Directory is still the repository root. Set it to `frontend` (§1).

### 7.7 Uploads of large files time out

CloudFront's origin response timeout applies to API calls (not to the direct-to-S3 PUTs). Large
media goes straight to S3 via signed URLs precisely to avoid this. If an API call itself times out
at ~30 s, raise the CloudFront distribution's **Origin response timeout** in the AWS console — see
`docs/QA_AUDIT.md`.

### 7.8 Session drops to the login page unexpectedly

`lib/api.ts` clears the stored token and redirects to `/login` on any `401` from an authenticated
request. That is expected when the JWT expires (`JWT_EXPIRES_MINUTES`, default 7 days) or after
`JWT_SECRET` is rotated on the backend — rotating it logs everyone out.

### 7.9 Nobody can log in, and nothing anywhere failed

Google returns `Error 401: invalid_client — The OAuth client was not found`, email/password login
fails too, and yet the backend is healthy, CORS is unchanged, the OAuth client is untouched and all
three CI stages are green. Every instinct here is wrong: the site is serving a bundle that was built
without its environment, because a `NEXT_PUBLIC_*` variable was typed **Sensitive** and
`vercel pull` refused to give it to the runner. Next.js inlined `undefined`, and nothing in the
toolchain treats that as an error.

Do not start in the Google console. Run the bundle grep in **§2.2.1** — if the client ID and the API
origin are not in the shipped JavaScript, no browser is sending them and the fault is the build.
Then fix the variable types per §2.2 and redeploy (§5).

A fresh occurrence of this exact fault should now fail the deploy rather than reach production
(§2.2). If you are reading this because it happened anyway, the interesting question is which of the
two assertions it slipped past, and the answer belongs in this section.

---

## 8. What makes this app Vercel-compatible (and what would break it)

Audited on the current tree:

- **No `app/api/**/route.ts` handlers** — nothing needs a serverless function of ours.
- **No server actions** (`"use server"` appears nowhere).
- **No `fs` / `node:*` imports in application code.** The only match is
  `frontend/scripts/pw-smoke.mjs`, a Playwright smoke script that is never imported by the app and
  never bundled.
- **No `output: "standalone"`.** That setting produces a self-hosted Node bundle and breaks the
  Vercel build — `frontend/next.config.ts` documents why it must stay off. It is only relevant if
  the frontend is ever moved onto the EC2 box or into a container.
- **Fonts are local** (`frontend/fonts/*.woff2` via `next/font/local`), so builds do not depend on
  a network fetch to Google Fonts.
- Every data-fetching page is a client component that calls the API from the browser, so no
  request-time secrets exist. ~~`next build` reports 30 of 33 routes as `○` (prerendered static);
  the three exceptions are the `[id]/edit` routes.~~ **Re-measured 2026-10-09: every page under a
  dynamic segment is `ƒ` (server-rendered on demand)**, which by then included the design-workshop
  pages, not three edit routes. No count is kept here, because the next page added moves it. List
  them with `find frontend/app -name page.tsx -path '*[*'`. They have a dynamic segment and no
  `generateStaticParams`, so Vercel deploys a small Node function that renders the client shell and
  hands off to the browser. It fetches nothing and reads no environment variable at request time.
  Adding `generateStaticParams` is not possible here (the id sets are unbounded), so this is
  expected, not a defect. **Where that function runs is the one thing that matters about it:** it
  sits between every visitor and every hard load or client-side navigation to those pages, so it is
  pinned to `bom1` (Mumbai) by `"regions"` in `frontend/vercel.json`. §1 has the measurement.

If you add any of the following, revisit this document: a route handler that proxies the API
(then CORS stops mattering but Vercel bandwidth starts to), `next/image` on a host not listed in
`images.remotePatterns` (hard runtime error), or a server component that reads the API at request
time (needs the API reachable from Vercel's servers, not just from the browser).

`frontend/next.config.ts` also sets response security headers on every route:
`Strict-Transport-Security` (production builds only, so a local HTTPS proxy can never pin
`localhost`), `X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`,
`Content-Security-Policy: frame-ancestors 'none'`, `Referrer-Policy: strict-origin-when-cross-origin`,
and a `Permissions-Policy` that deliberately **keeps** `geolocation`, `microphone`, `camera` and
`clipboard-write` enabled for our own origin — field capture (GPS tagging, audio recording, camera
file inputs) stops working if those get denied.

> Note the deliberate asymmetry with the API: the backend's `Permissions-Policy` denies all of those
> (`camera=()`, `microphone=()`, `geolocation=()`), because a JSON API has no use for them. The web
> origin is where capture actually happens, so it must not copy that policy.
> See [SECURITY.md §1.2](SECURITY.md).

---

## How this document is kept true

This document is mostly a **console runbook**, which makes it the hardest kind to keep honest: most
of what it describes lives in the Vercel dashboard and cannot be read from a checkout. So the rule
here is stricter than elsewhere — anything not checkable is marked, and anything that *can* be moved
out of the console and into an assertion should be.

| Claim class | Kept true by |
|---|---|
| The `NEXT_PUBLIC_*` variables and what each does | `frontend/lib/api.ts`, and the complete table in [ENVIRONMENT.md](ENVIRONMENT.md). One source; this document explains the *traps*, not the list. |
| §2.2 the Sensitive-vs-Encrypted trap | Now **enforced**, not just documented: stage 2 of the pipeline fails when a required value is missing from the pull, from the build, or from the served bundle ([CI.md §1](CI.md)). That is the model to follow — a trap that a runbook can only warn about is a trap that will be hit. |
| §3 the deploy flow | `.github/workflows/deploy-frontend.yml`. |
| §4.1 CORS, §4.2 bucket CORS, §4.3 Google origins | `BACKEND_CORS_ORIGINS` and the backend's `NEXT_PUBLIC_APP_URL` are pinned by `.github/workflows/deploy-backend.yml`, which fails the deploy if either line does not land and then asserts a real preflight from the production origin on the box. The other two are AWS and Google console state, **UNVERIFIED from here**. §7.3 is the symptom-side check for the first. |
| §8 what makes this Vercel-compatible | `frontend/next.config.ts`, plus the absence of route handlers and server actions. `grep -rl "use server\|export async function GET" frontend/app` returning nothing is the check, and it is the property the whole section rests on. The function region is `frontend/vercel.json`, in the repository. |
| Project settings: Root Directory, Git link, `createDeployments`, Node.js Version, env types, store connections | Dashboard state. Root Directory is **asserted** at deploy time. A Node.js Version whose major differs from the build's, and any database credential in the pulled environment, are **warned about** on every deploy. The rest are **UNVERIFIED** here. The 2026-10-09 measurements in §2 and §3 are dated so they can be re-taken, not trusted. |
| §3.1 which addresses are project domains | Dashboard state, **UNVERIFIED** from a checkout: **Project → Settings → Domains**, or `GET /v9/projects/{id}/domains` with a token. The workflow's `vercel alias set` step keeps the alias on each new deployment whatever the answer, and whether or not a rollback has turned auto-assignment off, so a wrong answer costs a rollback that does not move the site, not a stale publish. What a rollback does to auto-assignment is Vercel's documented behaviour (linked in §3.1), not something measured here. |

**Review triggers:** `frontend/next.config.ts`, `frontend/vercel.json`,
`.github/workflows/deploy-frontend.yml`, the pin step in `.github/workflows/deploy-backend.yml`, any
change of production address, or any new server-side code under `frontend/app`.

**Struck-through text is kept on purpose.** Where a statement was true and has stopped being true —
"leave previews on", "nothing downstream will tell you" — the correction sits next to it rather than
replacing it, because a reader who remembers the old advice needs to see that it changed, not
silently find different words.

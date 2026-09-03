# Standing the browser suite up on a developer machine

`frontend/e2e/README.md` is the reference for **what** the suite needs and why each variable exists.
This file is the narrower thing that was missing: the **order of operations** that actually works on
a Windows developer box, and the four ways it has been observed failing that look like product bugs
and are not.

Written 2026-08-22, while getting the signed-in specs to run for the first time on this machine.
Every number below was measured here; none is a guess.

## The sequence

Four things must be true before the first spec runs, and they must become true in this order,
because each one is what the next one talks to.

```bash
# 1. Postgres + MinIO. From the REPOSITORY ROOT and nowhere else — see the warning below.
docker compose up -d
docker compose ps                      # both must say (healthy) before you go on

# 2. The accounts the specs sign in as. Idempotent; run it as often as you like.
cd backend
DATABASE_URL='postgresql://postgres:postgres@127.0.0.1:55442/design_workshop' \
  PYTHONUTF8=1 ./.venv/Scripts/python.exe scripts/seed_test_accounts.py

# 3. The API, against that LOCAL database. Give it minutes, not seconds.
DATABASE_URL='postgresql://postgres:postgres@127.0.0.1:55442/design_workshop' \
  PYTHONUTF8=1 ./.venv/Scripts/python.exe -u -m uvicorn app.main:app --host 127.0.0.1 --port 8000

# 4. The web app. Separate terminal. BUILD, then serve the build — see "Why not next dev".
cd ../frontend
npx next build                         # minutes, once — not one compile per route
npx next start -p 3000                 # ready in 17-25 s here

# then, from frontend/
E2E_EMAIL=admin2@example.org E2E_PASSWORD='LocalDev123!' npx playwright test <spec>
```

Step 4 is the one that is not obvious, and it used to read `npm run dev` here — which the next
section shows never served `/login` on this machine at all. `npm run dev` remains the alternative to
try if `next dev` is healthy for you; the build is what is known to work on this disk.

`playwright test` now runs `e2e/support/preflight.ts` before the first spec. It probes the API's
`/health`, signs in for real (the only cheap proof that the database and the seeded accounts are both
there), asks MinIO's `/minio/health/live` whether object storage is up, and fetches the web app plus
one of the scripts the page it returns actually asks for. So a missing piece arrives as one sentence
naming it instead of a hundred failures that each name something else. It stays out of the way of
`npm run test:unit`, which is still allowed to need nothing at all.

**Two specimens of that rule from 2026-09-03, because both look like they should need a stack and do
not.** `backend/tests/test_media_presigned_reads.py` needs **neither a database nor a bucket** — the
encoder is a pure walk over an already-encoded dict, the flag is a pure read of settings, and
presigning is local HMAC — so it carries no `needs_db` and runs in the fast lane with everything else
that needs nothing. And `frontend/e2e/media-url-refresh-unit.spec.ts` is part of the `test:unit` set:
it ends in `-unit.spec.ts`, it is not one of that script's two excluded files, and it drives pure
functions plus a source-text pin rather than a browser. Neither needs step 1 or step 3 above.

### The host port is 55442 here, not the compose default

`docker-compose.yml` defaults to `55432`; the repository root `.env` overrides
`POSTGRES_HOST_PORT=55442` because something else on this machine had the default. Read the root
`.env` rather than trusting either number, and remember the API reaches Postgres on the **host**
port while a containerised API would reach it on `5432` inside the network.

### Never run `docker compose` from `backend/`

It picks up `backend/.env`, whose `DATABASE_URL` names the host port. The API container then comes up
unable to reach its own database and every spec fails with something that looks like an application
bug. (`backend/.env` also historically held the live Supabase DSN and production AWS keys. As of
this writing it is pointed at the local stack — `127.0.0.1:55442` and MinIO on `localhost:9010` —
but confirm that before you trust it, and pass `DATABASE_URL` inline anyway so you are never relying
on what a gitignored file happens to contain today.)

## Why not `next dev`

Measured on this machine, and the reason the suite skipped all night:

* A `next dev` server booted in **82 s** and then failed to serve `/login` **at all** — 41 polls
  over ten minutes plus three and a half minutes of direct `curl`, every one of them a connection
  timeout, while the process sat at ~34 % of one core and 3.0 GB resident.
* Its own log (`frontend/.next/dev/logs/next-development.log`) says what it was doing: after
  `○ Compiling /login ...` it emitted nothing but `✓ Finished filesystem cache database compaction`
  — nine times, the longest **2.8 min** — and Next itself warned
  `⚠ Slow filesystem detected. The benchmark took 457ms`. The repository lives on `D:`, the compile
  never finished, and the route never answered.

So on a machine with a slow disk, **build once and serve the build**:

```bash
cd frontend
npx next build            # one cost, not one per route
npx next start -p 3000
```

`next start` serves prebuilt routes, so no spec pays a compile and no spec is racing the cache
compactor. Two things to know about it:

* `NEXT_PUBLIC_API_URL` is **inlined at build time**. `frontend/.env.local` currently sets it to
  `http://localhost:8000`, which is what you want; if you change it you must rebuild, not restart.
* A build compiles the **whole** tree, so it fails on any broken import anywhere — including in a
  file some other task is halfway through renaming. `next dev` would not have noticed. If a build
  dies on `Module not found`, check whether the file has appeared since before believing it.

**Next refuses to run a second dev server in the same directory.** Starting one on another port
prints `⨯ Another next dev server is already running.` with the PID of the first. If a dev server is
already up and not answering, that one is the problem — stop it rather than starting another.

### `next start` does not survive somebody rebuilding underneath it

The one trap this approach adds, and it is worth knowing before it costs you an hour, because it
impersonates an application bug perfectly.

`next start` serves `.next` and holds the manifest it read at boot. Run `next build` again — in
another terminal, in another task, from anywhere in the same checkout — and that manifest now names
chunks that are no longer on disk. The running server answers **500** for them, in `text/plain`, so
the browser refuses the script, and the app's React error boundary paints its own page:

> **This page stopped before it finished** — Something went wrong while the app was drawing this
> page. It is a fault in the app, not something you did…

Every spec on that route then fails with `element(s) not found`, and the screenshot shows a polished
error screen that reads exactly like a bug in the feature under test. It is not. Measured here: the
browser console said

```
GET /_next/static/chunks/1xn1p9uduwx6x.js  500
ChunkLoadError: Failed to load chunk /_next/static/chunks/1xn1p9uduwx6x.js from module 64893
```

and that file did not exist — `.next/BUILD_ID` had been rewritten an hour after the server booted.
Ten of fifteen tests failed; restarting `next start` against the current `.next` and re-running the
identical probe returned seven editors and no error boundary, with nothing else changed.

**So: if a run that passed starts failing wholesale with "element(s) not found", check `.next/BUILD_ID`
against the time your server started before reading a single failure.** Restarting `next start`
takes about 20 seconds and is the whole fix.

## The failure modes that are not product bugs

### 1. Docker Desktop exits under load, and takes the database with it

Observed twice inside one hour. `docker compose ps` prints an **empty table** (containers stopped) or
fails outright with `failed to connect to the docker API at npipe:////./pipe/dockerDesktopLinuxEngine`
(the whole daemon is gone). The Postgres container log records it plainly:

```
LOG:  received fast shutdown request
FATAL:  terminating connection due to administrator command
LOG:  database system is shut down
```

The machine was running several pytest modules, a `tsc --noEmit`, an `eslint .` and a `next build` at
the time; free memory was 4.4 GB of 24 GB. Restarting Docker Desktop from
`C:\Program Files\Docker\Docker\Docker Desktop.exe` and waiting is the only fix, and the wait is
long — measured at **over twenty minutes** to bring the engine's named pipe back on a machine this
busy, with the `docker-desktop` WSL distro reporting `Running` for most of it and `docker version`
simply hanging.

### 2. The API does not recover from a database that went away — restart it

This one costs the most time because it looks like an application bug in whatever you were testing.
When the database disappears, `prisma-client-py`'s query engine subprocess dies, and the API does not
start another. Every request afterwards returns **HTTP 500** — and the exception is not about the
database, it is

```
httpx.ConnectError: All connection attempts failed
```

raised from `prisma/engine/_http.py`, i.e. the API failing to reach its own query engine. `GET /health`
keeps answering **200** throughout, because it touches no database at all. So:

> An API that passes `/health` and 500s on `/api/auth/login` has lost its query engine.
> Bring the database back, then **restart the API**. It will not heal on its own.

The preflight makes exactly this call for you, because the shape is unmistakable and the advice is
always the same.

### 3. A cold start is minutes, and a long silence is normal

`import app.services.stage_definitions` alone takes ~2 minutes here, so uvicorn prints nothing for
minutes before `Application startup complete.` Do not conclude "hung" without checking CPU. Two
notes for anyone scripting this:

* Run uvicorn through `python -u`. Without it the log file stays **0 bytes** while the process is
  perfectly healthy, which reads exactly like a hang.
* Same for the seed script: piping it into `tail` buffers all of its output until it exits.

`scripts/seed_test_accounts.py` is resilient to a database that is not up yet — it retries the
connection six times, and one run here survived the database vanishing mid-script and still finished
with `exit 0` and all six accounts `updated`.

### 4. Do not read a row count out of `pg_stat_user_tables`

Several specs skip themselves when the repository has no craft to assign a fixture artisan to
(`support/records.ts::anyCraftId` borrows one rather than naming one, and returns `null` when there
are none). Checking whether that is why a spec skipped is a reasonable thing to want. Doing it like
this is not:

```bash
# WRONG. Reported Craft = 0 on a database that has 396 of them.
docker exec design-workshop-postgres psql -U postgres -d design_workshop \
  -c "select relname, n_live_tup from pg_stat_user_tables where n_live_tup > 0 order by 2 desc;"
```

`n_live_tup` is a **statistics estimate**, and the collector had not caught up after the container
restarted, so `Craft` was absent from the listing entirely. Read on that, the local database looked
almost empty; it holds 396 crafts, 1,229 artisans and 21,971 design workshops. Count the rows:

```bash
docker exec design-workshop-postgres psql -U postgres -d design_workshop \
  -c 'select (select count(*) from "Craft") crafts, (select count(*) from "Artisan") artisans;'
```

There is no `Product` table on this schema — product documentation lives in `ProductDocumentation` —
so a query naming one errors rather than returning zero, which is its own useful signal.

## Before you believe a wall of red

In order, cheapest first:

1. `docker compose ps` from the repository root — an empty table, or a daemon error, explains everything.
2. `curl http://127.0.0.1:8000/health` and then a real `POST /api/auth/login`. 200 then 500 means §2.
3. `curl http://127.0.0.1:3000/login` — a connection timeout here is §"Why not `next dev`".
4. `.next/BUILD_ID` newer than your server's start time — that is the rebuild trap above. The
   preflight now catches its usual shape for you (it fetches one of the scripts the served page asks
   for, and a rebuilt-underneath server cannot produce it), but a stale-yet-intact build still looks
   perfectly healthy to it, so this stays on the list.
5. Only then read the failures.

---

## How this document is kept true

Almost nothing here is a claim about the product, which is the point: it is a record of what this
machine does, and a machine can be asked again.

| Claim class | Kept true by |
|---|---|
| The four commands, and their order — **now also executed weekly by a machine** | `.github/workflows/e2e-live.yml`, added 2026-09-03. It is this document's "The sequence" section, scripted, in its order and for its stated reasons, run every Monday and on demand — which makes it the thing that can be *asked again* rather than a person remembering to. If you change the sequence in one, change it in the other. **Two departures, both forced by CI and both documented in that file's own header:** the host ports are pinned to `55432` and `9010` in the job's `env:` (a runner has no repository-root `.env`, so the compose defaults apply and `55442` is an override on one laptop), and it runs `prisma migrate deploy`, which this document does not mention because the laptop's database already carried the schema. A runner's Postgres is empty every time. |
| The four commands, and their order | Run them. Each one either brings a port up or it does not. `e2e/support/preflight.ts` then re-asks four of these questions before the first spec — the API's `/health`, a real login (standing in for Postgres **and** the seeded accounts), MinIO's `/minio/health/live`, and the web app plus one script the page it serves asks for — so a sequence that has gone stale fails as a sentence rather than as a wall of red. It does **not** know which build `next start` is serving, and the storage probe warns rather than throwing, because a QR or stage-form run is perfectly valid without object storage. If you change the sequence here, change the preflight's remedy strings in the same edit; they are the copy a person actually reads, and they said `npm run dev` for a day after this document had stopped recommending it. |
| The DSN and the host port `55442` | The repository root `.env` (`POSTGRES_HOST_PORT`) and `docker-compose.yml`'s `${POSTGRES_HOST_PORT:-55432}`. **Re-read them rather than this file** — the default and the override differ, and which one applies is a property of the checkout, not of the documentation. |
| The account `admin2@example.org` and its password | `backend/scripts/seed_test_accounts.py` — `ACCOUNTS` and `PASSWORD`, in the file, not here. |
| The `next dev` measurements (82 s boot, `/login` never served, 2.8 min compactions) | `frontend/.next/dev/logs/next-development.log`, which Next writes itself. They are a snapshot of one machine on one night, not a property of Next: re-measure before repeating them, and if a dev server serves `/login` promptly for you, say so here rather than leaving a claim standing that your own machine disproves. |
| §1 Docker Desktop exiting, and the Postgres shutdown lines | `docker logs design-workshop-postgres`. The lines quoted are the container's own. |
| §2 the query engine not reconnecting | Reproducible on demand: stop the database under a running API, then call `/health` (200) and `/api/auth/login` (500). The traceback names `prisma/engine/_http.py`. If a future `prisma-client-py` restarts its engine, this section becomes wrong in the good direction — delete it and delete the preflight's 5xx remedy with it. |
| §4's row counts (396 crafts, 1,229 artisans, 21,971 workshops) | `select count(*)`, and **only** that. The whole point of the section is that the `pg_stat_user_tables` estimate said `Craft` was empty on the same database. These numbers date from 2026-08-23 and grow with every spec run; treat them as an order of magnitude, and never as a reason to believe a table is empty. |
| The rebuild trap (a 500 on a static chunk, `ChunkLoadError`, the error-boundary heading) | Reproducible in two minutes: start `next start`, run `next build`, reload. The heading quoted is the app's own text — `grep -rn "stopped before it finished" frontend/` finds the component that renders it. |

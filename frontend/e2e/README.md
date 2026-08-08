# Running the end-to-end suite

## The command

From `frontend/`, with the compose stack up and a dev server on :3000:

```bash
E2E_EMAIL=admin2@example.org E2E_PASSWORD='LocalDev123!' npx playwright test
```

That is the whole thing. Everything else — where object storage really lives, which origin the API
is on, the browser flags that make an upload work — is defaulted in `playwright.config.ts`, because
a variable each developer has to remember is a variable that will be forgotten and a suite that goes
red for a reason nobody can act on.

Bring the stack up first, **from the repository root and nowhere else**:

```bash
docker compose up -d          # run from the REPO ROOT
cd frontend && npm run dev    # separate terminal; the config never starts a server itself
```

> Running `docker compose` from `backend/` picks up `backend/.env`, whose `DATABASE_URL` names the
> **host** port. The API then comes up unable to reach its own database, and every spec fails with
> something that looks like an application bug.

## The variables, and why each one exists

| Variable | Default | Why it exists |
| --- | --- | --- |
| `E2E_EMAIL` | *(none — specs skip)* | The specs sign in against a real API. A checked-in password would be a credential in git, so there is no default and the signed-in specs `test.skip` without one. `admin2@example.org` is the account to use: it is an ADMIN, and several specs assert on controls only an admin is offered (the media queue's Retry, for one). |
| `E2E_PASSWORD` | *(none — specs skip)* | Same. |
| `E2E_BASE_URL` | `http://localhost:3000` | The app under test. Point it at a deployment to run the same specs there. |
| `E2E_API_URL` | `http://localhost:8000` | Backend ORIGIN, no `/api`. The specs build their own fixtures through it. |
| `NEXT_PUBLIC_API_URL` | defaults to `E2E_API_URL` | Read by the **Playwright** process, not just the app. `carry-context.spec.ts` calls the API from inside the page and, unset, falls back to a production CloudFront origin — so leaving it unset did not merely fail, it pointed a local run at production data. The config now defaults it so that fallback is never reached. |
| `E2E_OBJECT_STORE_MAP` | `minio:9000 127.0.0.1:9010` on a local stack | See below. |
| `E2E_MINIO_PORT` | `9010` | The host port the compose file publishes MinIO on. |
| `E2E_WORKERS` | `1` | Raising it is possible but not free — see "Why one worker". |
| `E2E_RETRIES` | `0` | Zero on purpose. A retry turns a real intermittent defect into a green run, and this suite exists to be believed. Pass `--retries=1` when triaging, so Playwright labels "failed then passed" as flaky instead of leaving you to guess. |

Optional, and the specs that want them skip cleanly when they are absent — `E2E_WORKSHOP_ID`
(`report-download`), `E2E_MAP_API` / `E2E_MAP_TOKEN` (`zz-map-scopes`), `E2E_LOCAL_API`
(`zzz-consolidated-shot`).

## Uploads: why the browser is given a resolver rule

`POST /media/presign` returns a URL naming the endpoint the **API** is configured with. In the
compose stack that is `http://minio:9000` — a name that resolves inside the docker network and
nowhere else — so every browser upload on a developer machine died with `ERR_NAME_NOT_RESOLVED`, and
every spec touching a photograph, a signature or an attachment failed for a reason that had nothing
to do with what it was testing.

Rewriting the URL does not work, and this is measured rather than assumed: SigV4 signs the `Host`
header, so the same signature pointed at `127.0.0.1:9010` comes back `403 SignatureDoesNotMatch`,
while the identical request with the host name left alone and only the *connection* redirected comes
back `200`. So the fix is name resolution, not rewriting — Chromium is launched with
`--host-resolver-rules=MAP minio:9000 127.0.0.1:9010`, which changes where the socket goes and
leaves the signed header untouched. `localhost:9000` is mapped for the same reason: `presign` also
hands back a `publicUrl` on that origin and nothing is published on host port 9000 either.

This is set once, in `playwright.config.ts`. A spec should not reinvent it, and in particular should
not open a TCP forwarder to do it by hand — `test.use({ launchOptions })` **replaces** the config's
flags rather than adding to them, so a spec that declares its own must include the mapping itself.

## Fixtures: build the record, do not name it

A spec that needs a record should create it through the API in `beforeAll` and delete it in
`afterAll`. `support/records.ts` has the helpers. The reason is not tidiness: specs used to open
records by ids copied out of the production database, which 404 against a local one — so the spec
reported a failure about location cards that was really a failure about which database was in front
of it, and every one of those ids was one `prisma migrate reset` away from being wrong for everybody.

Two things the API will not let a fixture do, both deliberate:

- **A create with no location is refused**, and refused again if the location has no state and
  district. Records that predate that rule can only be *found*, never built — `findArtisan` in
  `support/records.ts` searches for one and returns null so the spec can skip with a reason.
- **A location cannot be removed** by a later update, but its state and district *can* be emptied.
  `unstateTheAddress` does that, which is how the "coordinate with no stated address" row is
  reproduced without pretending the create rule is weaker than it is.

## Signing in

Use `signIn` from `support/session.ts`. It plants the token the app reads rather than driving the
login form, for two reasons that both used to show up as mystery failures:

- **Hydration.** On a dev server compiling under load the sign-in button paints before React has
  attached its handler, so the click submits nothing and the spec dies sixty seconds later in
  `waitForURL` — a failure that names `/login` while actually reporting that the page was not yet
  interactive.
- **The login throttle.** The API rate-limits repeated logins from one address. A spec that drives
  the form *and* calls the API for a token logs in twice per test; past a few tests the form
  re-renders `/login` with no visible error, which reads as "wrong credentials" and is not.

The login form itself is still covered — `a11y-barriers.spec.ts` drives it on purpose, which is
where a test of the form belongs.

## Why one worker

`fullyParallel` is off and `workers` is 1 because the specs sign in as the same account and several
share the showcase workshop, and because the API rate-limits repeated logins from one address —
which is the first thing that breaks when the worker count goes up. Raise `E2E_WORKERS` if you like,
but treat a burst of sign-in failures as the expected symptom rather than a regression.

## When a spec cannot run here

Skip with a message that names what is missing (`test.skip(!id, "no artisan without a location row
exists on this database — the mandatory-location rule refuses to create one")`). Never fail. A red
suite that everyone learns to ignore is worse than a smaller green one, and a failure that means
"you forgot an env var" trains people to ignore the ones that mean something.

## A note about this machine

A full run needs the API for its whole length. On a developer box that is also building Android or
running pytest, WSL2 has been observed terminating the Docker VM part-way through — after which
every remaining spec fails for a reason that is not in this repository. If a run produces a wall of
failures, check `docker compose ps` **before** believing any of them.

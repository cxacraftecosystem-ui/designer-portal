import type { FullConfig } from "@playwright/test";

/**
 * One honest sentence instead of a hundred lying ones.
 *
 * THE PROBLEM THIS SOLVES, and it is the most expensive problem this suite has. Nothing in the
 * config starts a server (deliberately — see the header of `playwright.config.ts`), so a run
 * against a stack that is not up does not report "the stack is not up". It reports whatever each
 * spec happens to notice first: `signIn` throws on a login that never answered, a fixture throws on
 * a 500, a locator times out at fifteen seconds because the page never painted. The result is a
 * wall of unrelated failures whose only real content is one fact nobody stated, and the README's
 * advice — "if a run produces a wall of failures, check `docker compose ps` BEFORE believing any of
 * them" — is advice a person has to remember. This checks it for them, once, before the first spec.
 *
 * IT IS NOT A SKIP GATE, and the difference matters. Missing credentials are still handled by
 * `test.skip` inside each spec, because a machine with no `E2E_EMAIL` was never promised a signed-in
 * run and a red result meaning "you forgot an env var" trains people to ignore red results. But a
 * run that HAS credentials has asked for the real thing, and a missing API is not a thing to shrug
 * at — it is the answer, and it should arrive as the answer rather than as a symptom.
 *
 * WHY THE API PROBE IS `/health` AND NOT A LOGIN. `/health` touches no database on purpose, which
 * is exactly why it is not enough on its own: this stack has twice come up with the API answering
 * `/health` 200 while every real request 500'd, because Docker Desktop had stopped underneath it
 * and prisma-client-py's query engine had exited without the API noticing. So `/health` proves the
 * process is listening, and one real login proves the whole chain — process, query engine, database,
 * and the seeded account — is actually there. The login also warms the API's first-request import
 * cost, which used to be paid, and occasionally timed out, inside whichever spec ran first.
 *
 * The timeouts are generous because this is measured on a slow machine: a cold FastAPI import chain
 * takes minutes here, and a Next dev server compiling its first route has been observed taking
 * longer than that. Being slow is not the failure being reported.
 *
 * WHAT IT CHECKS, EXACTLY — four probes, because the four things `docs/TESTING-E2E-LOCAL.md` tells
 * you to start are four separate ways for a run to be meaningless: the API answers `/health`; a real
 * login succeeds (which is the only cheap proof that the database and the seeded accounts are both
 * there); object storage answers, because it is a whole container that can be missing while
 * everything else is up and every media, signature and attachment spec then fails for a reason that
 * has nothing to do with what it tests; and the web app serves a page whose scripts still exist.
 *
 * AND ONE THING IT DELIBERATELY CANNOT CHECK: whether the running `next start` is the build you
 * think it is. The chunk probe below catches the common shape of that (a manifest naming files a
 * later `next build` deleted) but not a server that is merely serving an OLDER intact build from a
 * different directory. If a run that passed starts failing wholesale with "element(s) not found",
 * restart `next start` before reading a single failure — see the doc's triage list.
 */

const PROBE_TIMEOUT_MS = Number(process.env.E2E_PREFLIGHT_TIMEOUT_MS ?? 60_000);

async function reach(url: string, init?: RequestInit): Promise<Response> {
  const control = new AbortController();
  const timer = setTimeout(() => control.abort(), PROBE_TIMEOUT_MS);
  try {
    return await fetch(url, { ...init, signal: control.signal });
  } finally {
    clearTimeout(timer);
  }
}

function fail(what: string, detail: string, remedy: string): never {
  throw new Error(
    [
      "",
      `E2E PREFLIGHT FAILED — ${what}`,
      "",
      detail,
      "",
      remedy,
      "",
      "Nothing was run. See frontend/e2e/README.md, and docs/TESTING-E2E-LOCAL.md for the exact",
      "command sequence that stands this stack up.",
      ""
    ].join("\n")
  );
}

/**
 * True when this run selected nothing but `-unit` specs, which need no services at all.
 *
 * `npm run test:unit` is the CI gate, and its whole promise is that it runs on a machine with no
 * database, no API and no dev server — so it must not start demanding one just because the developer
 * who typed it happens to have `E2E_EMAIL` exported in their shell. The selection is expressed as a
 * positional file filter (see the pattern in `package.json`, written out in e2e/README.md), so that
 * is what this reads.
 *
 * Deliberately conservative in one direction only: a filter this fails to recognise is treated as a
 * full run and gets the preflight, which is the behaviour that was there before.
 *
 * TWO `-unit` SPECS DO NEED A SERVER, and the `-unit` substring cannot see it. This comment used to
 * say they "are excluded from `test:unit` BY NAME, so they never arrive here inside a unit-only
 * selection" — true of `npm run test:unit` and of nothing else. `npx playwright test
 * e2e/login-credential-floor-unit.spec.ts` is the ordinary triage gesture, it is a unit-only
 * selection by the test below, and that spec opens `/login` in a browser (as does
 * `access-refusal-unit`). So the selection most likely to be typed by hand was the one getting no
 * preflight, and it produced exactly the wall of red this file exists to replace. Named here
 * instead of inferred, because the naming convention is what was wrong, not the reading of it.
 */
const SERVER_BACKED_UNIT_SPECS = ["access-refusal-unit", "login-credential-floor-unit"];

/**
 * True when a positional filter asks for one of the two `-unit` specs that need a web server.
 *
 * `test:unit`'s own pattern MENTIONS both names — inside a negative lookahead that EXCLUDES them
 * (read it in `package.json`) — so a plain substring test would take the CI gate's filter for a
 * request for them and demand a stack for the one job whose promise is needing none. A lookahead is
 * therefore read as what it is: an exclusion, not a request.
 */
function namesAServerBackedUnitSpec(filter: string): boolean {
  if (filter.includes("(?!")) return false;
  return SERVER_BACKED_UNIT_SPECS.some((name) => filter.includes(name));
}

function unitOnlySelection(): boolean {
  // argv is ["node", "…/@playwright/test/cli.js", "test", <filters and flags…>] — MEASURED, printed
  // from inside a throwaway globalSetup, because getting this wrong is silent in the direction that
  // matters: leave the subcommand in and it counts as a filter naming no `-unit`, so `every` is
  // false, so the CI unit job demands a stack and goes red on a machine that was never given one.
  const argv = process.argv.slice(process.argv[2] === "test" ? 3 : 2);
  const filters = argv.filter((arg) => !arg.startsWith("-"));
  if (filters.length === 0) return false;
  if (filters.some(namesAServerBackedUnitSpec)) return false;
  return filters.every((arg) => arg.includes("-unit"));
}

export default async function preflight(config: FullConfig): Promise<void> {
  if (unitOnlySelection()) return;

  const email = process.env.E2E_EMAIL ?? "";
  const password = process.env.E2E_PASSWORD ?? "";
  // No credentials means no signed-in spec will run at all; every one of them skips itself. There is
  // nothing for this to protect, and demanding a stack for a run that is about to skip would break
  // the `test:unit` job, which is supposed to work on a machine with no services whatsoever.
  if (!email || !password) return;

  const api = process.env.E2E_API_URL ?? "http://localhost:8000";
  const web = config.projects[0]?.use?.baseURL ?? process.env.E2E_BASE_URL ?? "http://localhost:3000";

  let health: Response;
  try {
    health = await reach(`${api}/health`);
  } catch (error) {
    fail(
      `the API at ${api} did not answer`,
      `GET ${api}/health — ${(error as Error).message}`,
      "Start it, from backend/, with the LOCAL database named inline (never let it read a deployed DSN):\n" +
        "  DATABASE_URL='postgresql://postgres:postgres@127.0.0.1:55442/design_workshop' \\" +
        "\n    PYTHONUTF8=1 ./.venv/Scripts/python.exe -u -m uvicorn app.main:app --host 127.0.0.1 --port 8000\n" +
        "The `-u` is not decoration: without it the log stays 0 bytes for the minutes the import chain\n" +
        "takes, which reads exactly like a hang (docs/TESTING-E2E-LOCAL.md, section 3)."
    );
  }
  if (!health.ok) {
    fail(`the API at ${api} answered /health with ${health.status}`, await health.text(), "Read the API's own log: it is the only place the reason is written.");
  }

  let login: Response;
  try {
    login = await reach(`${api}/api/auth/login`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ email, password })
    });
  } catch (error) {
    fail(
      `the API at ${api} is listening but cannot be signed in to`,
      `POST ${api}/api/auth/login — ${(error as Error).message}`,
      "This is the shape a dead database takes: /health needs no database and passes anyway."
    );
  }
  if (!login.ok) {
    const body = await login.text();
    // A 500 here is almost never the credentials. It is the database, and on this stack it has twice
    // been Docker Desktop exiting under load — after which prisma-client-py's query engine is gone
    // and the API never recovers on its own, so restarting the API is part of the fix, not optional.
    const remedy =
      login.status >= 500
        ? "The API is up and the database is not. Check `docker compose ps` from the REPOSITORY ROOT\n" +
          "(an empty listing means the containers are stopped, or Docker Desktop itself has exited),\n" +
          "then `docker compose up -d` — and RESTART THE API afterwards: its query engine does not\n" +
          "come back by itself once the database has gone away."
        : `Seed the accounts these specs sign in as:\n` +
          "  cd backend && DATABASE_URL='postgresql://postgres:postgres@127.0.0.1:55442/design_workshop' \\" +
          "\n    PYTHONUTF8=1 ./.venv/Scripts/python.exe scripts/seed_test_accounts.py";
    fail(`signing in as ${email} was refused with ${login.status}`, body.slice(0, 500), remedy);
  }

  /*
    OBJECT STORAGE IS A SEPARATE CONTAINER AND FAILS SEPARATELY.

    `docker compose up -d` starts two things, and only one of them is proved by a login. MinIO can be
    unhealthy, or its port unpublished, with Postgres and the API perfectly fine — and then every
    spec that touches a photograph, a signature or an attachment fails inside an upload, naming the
    control it was testing. `playwright.config.ts` goes to real lengths to make the browser reach
    this host (the resolver rule and the reason SigV4 forces one are in its header); this asserts the
    host is actually there before a spec relies on it.

    The address is read out of `E2E_OBJECT_STORE_MAP` — the value the config computed and exported,
    whose second token is precisely where the browser will be sent — so this cannot drift from the
    mapping the run will really use. Empty means the run is pointed at a deployment, where the
    endpoint the API hands out is genuinely reachable and there is nothing local to check.

    `/minio/health/live` is MinIO's own liveness route and needs no credentials: measured here, HEAD
    returns 200 with `Server: MinIO`. A failure is NOT fatal to every spec, so this one reports and
    does not throw — a run of the QR sheet or the stage forms is perfectly valid without storage,
    and turning a partial gap into "nothing was run" would be its own lie.
  */
  const objectStoreMap = process.env.E2E_OBJECT_STORE_MAP ?? "";
  const objectStoreHost = objectStoreMap.trim().split(/\s+/)[1] ?? "";
  if (objectStoreHost) {
    let storeReached = false;
    try {
      const store = await reach(`http://${objectStoreHost}/minio/health/live`, { method: "HEAD" });
      storeReached = store.ok;
    } catch {
      storeReached = false;
    }
    if (!storeReached) {
      console.warn(
        [
          "",
          `E2E PREFLIGHT WARNING — object storage at ${objectStoreHost} did not answer /minio/health/live`,
          "",
          "Specs that upload a photograph, a signature or an attachment will fail inside the upload,",
          "naming whatever control they were testing. `docker compose up -d` from the REPOSITORY ROOT",
          "starts it (design-workshop-minio); `docker compose ps` must say (healthy).",
          ""
        ].join("\n")
      );
    }
  }

  let landing: Response;
  try {
    landing = await reach(web);
  } catch (error) {
    fail(
      `the web app at ${web} did not answer`,
      `GET ${web} — ${(error as Error).message}`,
      // NOT `npm run dev`, which is what this said until 2026-08-23 and is the failure mode the
      // whole document exists to escape: on this machine `next dev` booted in 82 s and then never
      // served /login at all, through 41 polls over ten minutes. Build once, serve the build.
      "Start it from frontend/, and serve a BUILD rather than compiling per route:\n" +
        "  npx next build      # one cost, minutes, not one compile per route\n" +
        "  npx next start -p 3000\n" +
        "`next start` was ready in 17-25 s here and served every route immediately. `npm run dev`\n" +
        "(`next dev`) is the documented alternative and has NOT worked on this disk — see\n" +
        "docs/TESTING-E2E-LOCAL.md, \"Why not next dev\", for the measurements. Next also refuses a\n" +
        "second dev server in the same directory: if one is already up and not answering, that one is\n" +
        "the problem — stop it rather than starting another."
    );
  }
  if (!landing.ok && landing.status >= 500) {
    fail(`the web app at ${web} answered ${landing.status}`, await landing.text(), "Read the web server's own output.");
  }

  /*
    IS THE RUNNING SERVER STILL SERVING A BUILD THAT EXISTS?

    The trap this catches cost an hour and impersonates a product bug perfectly. `next start` holds
    the manifest it read at boot; another task runs `next build`; the chunks that manifest names are
    deleted, the server answers them with an error in `text/plain`, and the app's own error boundary
    paints "This page stopped before it finished". Every spec on the route then fails with
    "element(s) not found" over a screenshot of a polished error screen.

    So: take a script URL out of the page the server just rendered and ask for it. Verified in both
    directions on this machine — a chunk the build really has answers 200, and a chunk name that is
    not on disk answers 404 `text/plain`; the run that produced the doc's account of this saw 500 for
    the same reason. What is NOT verified here is the full trap end to end (that needs a rebuild
    under a live server, which is minutes), which is why the failure text says "restart it" rather
    than claiming to know which build is which.

    Silent when no script URL can be found: a deployment, a different framework version or a
    redirect body is not evidence of anything, and a preflight that guesses is worse than none.
  */
  let missingAsset: { path: string; status: number } | null = null;
  try {
    const html = await landing.text();
    const asset = /"(\/_next\/static\/[^"]+\.js)"/.exec(html)?.[1];
    if (asset) {
      const chunk = await reach(new URL(asset, web).toString());
      if (!chunk.ok) missingAsset = { path: asset, status: chunk.status };
    }
  } catch {
    // Reading the body or resolving the URL failed. Not a verdict about the stack; say nothing.
  }
  // OUTSIDE the try, and it matters: `fail` throws, and the first version of this had the call
  // inside — so the catch above swallowed the very verdict it was reaching. Measured, not guessed: a
  // throwaway server serving a page whose one script 404s let the run proceed and produced exactly
  // the "element(s) not found" wall this probe exists to replace.
  if (missingAsset) {
    fail(
      `the web app at ${web} is serving a build whose scripts are gone (${missingAsset.path} answered ${missingAsset.status})`,
      "The page renders and its JavaScript 404s or 500s, so the browser gets the app's error\n" +
        "boundary instead of the app. This is what a `next build` run underneath a live\n" +
        "`next start` looks like — the served manifest names chunks that no longer exist.",
      "Restart the server against the build that is on disk now:\n" +
        "  npx next start -p 3000\n" +
        "About 20 seconds, and it is the whole fix. If you did not rebuild, check whether another\n" +
        "task did (docs/TESTING-E2E-LOCAL.md, \"next start does not survive somebody rebuilding\")."
    );
  }
}

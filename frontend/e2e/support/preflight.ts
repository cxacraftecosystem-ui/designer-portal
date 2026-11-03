import type { FullConfig } from "@playwright/test";

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

const SERVER_BACKED_UNIT_SPECS = ["access-refusal-unit", "login-credential-floor-unit"];

function namesAServerBackedUnitSpec(filter: string): boolean {
  if (filter.includes("(?!")) return false;
  return SERVER_BACKED_UNIT_SPECS.some((name) => filter.includes(name));
}

function unitOnlySelection(): boolean {
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

  let missingAsset: { path: string; status: number } | null = null;
  try {
    const html = await landing.text();
    const asset = /"(\/_next\/static\/[^"]+\.js)"/.exec(html)?.[1];
    if (asset) {
      const chunk = await reach(new URL(asset, web).toString());
      if (!chunk.ok) missingAsset = { path: asset, status: chunk.status };
    }
  } catch {
  }
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

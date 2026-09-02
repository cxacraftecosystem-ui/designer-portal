// ─── LIVE AGAIN AS OF 2026-09-02. THIS SCRIPT'S CALLER RUNS NIGHTLY ───────────────────────────────
//
// Invoked by `npm run keep-alive`, which is invoked by `.github/workflows/keep-supabase-active.yml`
// — dormant from 2026-08-22 (schedule commented out, no secret) and re-armed on 2026-09-02 when
// production moved back onto a Supabase project. THAT FILE'S HEADER IS THE ONE TO READ for the
// story; "The database" in docs/ENVIRONMENT.md is the one place the current provider is named.
//
// WHY IT EXISTS: a free-tier Supabase project PAUSES after a stretch of inactivity and needs a
// human to restore it, so a nightly query keeps it awake.
//
// THE MECHANISM BELOW IS UNCHANGED FROM THE PRE-2026-08-22 ERA, DELIBERATELY. Every line — the
// `:5432 → :6543` pooler rewrite, the EMAXCONNSESSION reasoning, the retry backoff — is specific to
// one provider's pooler, and that is the point rather than a defect: this file is the workaround,
// not the configuration. ONE OPERATIONAL CONSTRAINT LEARNED ON RE-ARMING, 2026-09-02: the URL in
// the secret must NOT carry `sslmode=require` — pg 8.16+'s libpq-compat path then verifies the
// pooler's provider-signed chain and fails with SELF_SIGNED_CERT_IN_CHAIN, overriding the
// `rejectUnauthorized: false` below. TLS still happens; this client just does not verify the chain,
// which is an accepted trade for a nightly `select now()` and nothing else.

import pg from "pg";

const { Client } = pg;

function requiredEnv(names) {
  for (const name of names) {
    const value = process.env[name];
    if (value && value.trim()) return value.trim();
  }
  throw new Error(`Missing required database URL. Set one of: ${names.join(", ")}`);
}

// The keep-alive does a single short query, so it must NOT use the Supabase
// SESSION pooler (port 5432, pool_size 15). In session mode each client holds its
// slot for the whole connection, and the live backend already saturates those 15
// slots with long-lived Prisma sessions — so a session-mode keep-alive is rejected
// with `(EMAXCONNSESSION) max clients reached in session mode`.
//
// The TRANSACTION pooler (port 6543) is the right place for short-lived/serverless
// connections: the server connection is released after each transaction, so it
// multiplexes many brief clients and never competes with the app's session slots.
// We auto-route the pooler host from :5432 -> :6543 unless explicitly told not to.
function toTransactionPooler(connectionString) {
  if (process.env.SUPABASE_KEEPALIVE_NO_REWRITE === "true") return connectionString;
  // Only rewrite the Supabase pooler host:port pair (never anything inside the password).
  return connectionString.replace(
    /(@[^/@]*\.pooler\.supabase\.com):5432\b/i,
    "$1:6543"
  );
}

const rawConnectionString = requiredEnv([
  "SUPABASE_KEEPALIVE_URL",
  "SUPABASE_DATABASE_URL",
  "DATABASE_URL",
]);
const connectionString = toTransactionPooler(rawConnectionString);
if (connectionString !== rawConnectionString) {
  console.log("Routing keep-alive through the Supabase transaction pooler (:6543).");
}

const sslOption = process.env.SUPABASE_DB_SSL === "false" ? false : { rejectUnauthorized: false };

async function pingOnce() {
  const client = new Client({
    connectionString,
    ssl: sslOption,
    // Fail fast instead of hanging the whole job if the pooler is unhealthy.
    connectionTimeoutMillis: 15_000,
    statement_timeout: 15_000,
    query_timeout: 15_000,
    // PgBouncer/Supavisor transaction mode does not support session-pinned features;
    // a plain `select now()` is safe regardless, but keep the client minimal.
    application_name: "supabase-keep-alive",
  });
  try {
    await client.connect();
    const result = await client.query("select now() as server_time_utc");
    console.log(`Supabase keep-alive OK at ${result.rows[0].server_time_utc.toISOString()}`);
  } finally {
    await client.end().catch(() => {});
  }
}

// Even on the transaction pooler a transient `EMAXCONNSESSION`/network blip is
// possible the instant the pool is churning. Retry a few times with backoff so a
// one-off blip never fails the scheduled job.
const MAX_ATTEMPTS = 5;
let lastError;
for (let attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
  try {
    await pingOnce();
    process.exit(0);
  } catch (error) {
    lastError = error;
    const code = error?.code ? ` (${error.code})` : "";
    console.warn(`Keep-alive attempt ${attempt}/${MAX_ATTEMPTS} failed${code}: ${error?.message ?? error}`);
    if (attempt < MAX_ATTEMPTS) {
      const delayMs = Math.min(15_000, 2_000 * attempt);
      await new Promise((resolve) => setTimeout(resolve, delayMs));
    }
  }
}

console.error("Supabase keep-alive failed after all attempts.");
throw lastError;

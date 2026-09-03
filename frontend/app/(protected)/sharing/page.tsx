"use client";

import { useCallback, useEffect, useMemo, useState } from "react";
import { AlertTriangle, Check, Share2, X } from "lucide-react";

import { deleteConfirm, useConfirm } from "@/components/dialogs/ConfirmDialog";
import { EmptyState } from "@/components/EmptyState";
import { Field, Select, TextInput } from "@/components/FormControls";
import { PageHeader } from "@/components/PageHeader";
import { RowActions, rowAction } from "@/components/RowActions";
import { useAuth } from "@/components/AuthProvider";
import { SearchableMultiSelect, type SelectOption } from "@/components/ui/SearchableSelect";
import type { ProcessRecord } from "@/components/forms/ProcessForm";
import { apiFetch, listResource } from "@/lib/api";
import { runPerPerson, type BatchOutcome, type BatchTarget } from "@/lib/sharingBatch";
import {
  TIER_RANK,
  classifyChange,
  nameList,
  plural,
  scopeKey,
  scopeRemoval,
  scopeWords,
  splitScopeKey,
  standingsBy,
  type Scope,
  type Standing
} from "@/lib/sharingScope";
import type {
  Artisan,
  Craft,
  DataAccessGrant,
  DataAccessTier,
  MediaFile,
  MyGrants,
  PageResult,
  ProductDocumentation,
  QuestionnaireInterview,
  TierInfo,
  ToolDocumentation,
  User,
  Workshop
} from "@/lib/types";

const TIER_LABEL: Record<DataAccessTier, string> = {
  DOWNLOAD: "Download (minimum)",
  COMMENT: "Comment (medium)",
  EDIT: "Edit (maximum)"
};

/* ────────────────────────────────────────────────────────────────────────────
 * The record types a subset grant can name
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * The API's page ceiling, which is also this picker's.
 *
 * Every one of these list routes declares `pageSize: int = Query(20, ge=1, le=100)`, so 100 is not a
 * number chosen here — it is the largest page the server will answer, and asking for more is a 422.
 * That makes truncation a certainty rather than a risk on a full deployment (measured against the
 * running API: 431 artisans, 613 products), which is why `RecordGroup.total` is carried and printed
 * beside every heading. See the note under the list for why a capped list is dangerous HERE
 * specifically and not merely inconvenient.
 */
const PICKER_PAGE_SIZE = 100;

/**
 * One record offered for a subset grant. `recordType` is the string the API stores in
 * `DataAccessScopeItem.recordType` and looks up in `RECORD_DELEGATES` — lowercase, always.
 */
type OwnRecord = { recordType: string; recordId: string; name: string };

/** One type's rows, how many exist, and whether the list arrived at all. */
type RecordGroup = {
  recordType: string;
  /** The heading over these rows. */
  heading: string;
  /** The type read out in prose, e.g. in a removal warning: "Product · Blue lassi jar". */
  noun: string;
  /**
   * What a grant on THIS type actually reaches, where that is narrower than the tier implies. Null
   * for the five types every tier honours end to end. See {@link RECORD_REACH}.
   */
  reach: string | null;
  /** The rows that can actually be ticked. */
  records: OwnRecord[];
  /**
   * How many rows the query matched, as the API counted them. Null when the list did not load.
   *
   * COMPARED AGAINST {@link fetched}, NOT AGAINST `records.length`. The two differ when a deployment
   * ignores the `createdBy` parameter and the client-side owner filter does the narrowing instead —
   * and in that case `total` is a count of the whole table, so `total > records.length` would print
   * a truncation that is really an ownership filter. `total > fetched` is true of the page and only
   * of the page, on both kinds of deployment.
   */
  total: number | null;
  /** How many rows this page of the API actually returned, before the owner filter. */
  fetched: number;
  /** The reason this type's list is missing, when it is. */
  failed: string | null;
};

/**
 * WHAT A SUBSET GRANT ON EACH TYPE ACTUALLY REACHES — read off the backend, not assumed from the
 * fact that the type is accepted.
 *
 * `RECORD_DELEGATES` in `backend/app/api/routes/data_access.py:33-43` accepts eight record types
 * (nine names — `questionnaireinterview` is an alias of `questionnaire`), and `_scope_create` stores
 * whatever it is given. But "the scope item is stored" and "the scope item changes what a colleague
 * can do" are two different claims, and they come apart for three of the eight. Traced 2026-08-28:
 *
 *  * COMMENT is uniform. `POST /data-access/comments` resolves the record's owner through
 *    `RECORD_DELEGATES` and asks `effective_tier_for_record`, whose `_grant_covers` matches a scope
 *    item by `(recordType, recordId)` with no per-type knowledge at all. All eight work.
 *  * EDIT is seven of the eight. `guard_record_edit(..., record_type)` is called from `artisans.py`,
 *    `crafts.py`, `processes.py`, `products.py`, `questionnaire.py`, `tools.py` and `workshops.py`.
 *    There is no such call anywhere in `media.py`, so an EDIT-tier scope item on a media file
 *    confers commenting and nothing more.
 *  * DOWNLOAD is five of the eight, and this is the one worth printing. `GET /export/dataset` reads
 *    six tables and filters exactly five of them against the grant
 *    (`export.py:280-284` — workshop, artisan, product, tool, questionnaire). Crafts are never read
 *    as records at all; they appear only as folder names built from the workshops and artisans in
 *    the archive. Processes ARE in the archive, emitted under their PRODUCT
 *    (`processes_by_product`, `export.py:437-465`), so a process arrives when its product is granted
 *    and not otherwise. Media is fetched by the records that survive the filter, and unclaimed files
 *    are deliberately dropped under a subset grant so a grantee holding two of fifty artisans does
 *    not receive the photography of the other forty-eight.
 *
 * So the three types this picker gained are real and worth having — commenting on a colleague's
 * process is exactly the collaboration this page is for — and printing them without saying where
 * they stop would be the worse half of adding them. `designWorkshop` and `prototype` are absent on
 * purpose and must stay absent: a design workshop is shared through `DesignWorkshopViewer`, which is
 * a different table with a different rulebook.
 */
const RECORD_REACH = {
  full: null,
  craft:
    "Comment and edit. Crafts are not separately downloadable — the archive builds its craft folders " +
    "from the artisans and workshops already in it.",
  process:
    "Comment and edit. A process reaches a download with its PRODUCT, so share the product too if the " +
    "archive is what your colleague needs.",
  media:
    "Comment only — media has no edit route to grant. A file reaches a download with the record it " +
    "hangs off."
} as const;

/**
 * WHY THE TWO TIER PICKERS STAY SINGLE-SELECT while the two people pickers became multi.
 *
 * The tiers are cumulative, not a set of independent permissions — see `TIER_RANK` in
 * `lib/sharingScope.ts`, which mirrors `TIER_ORDER` in `backend/app/services/access.py`. A person
 * holds exactly one rung, so a multi-select of "DOWNLOAD and EDIT" would have no meaning to express;
 * it is just EDIT, and offering it would invite an owner to think they had granted something
 * narrower than they had.
 */

/** What a tier lets someone actually DO, for the one sentence in the confirm dialog. */
const TIER_CONSEQUENCE: Record<DataAccessTier, string> = {
  DOWNLOAD: "download",
  COMMENT: "download and comment on",
  EDIT: "download, comment on and edit"
};

/**
 * From how many people an action stops being routine and gets a confirm.
 *
 * Two, not one. Granting a single colleague has never asked for confirmation and must not start:
 * this is the everyday act the page exists for, it is visible in the table underneath the moment it
 * lands, and one Revoke click undoes it. What earns the interruption is that a single "Select all"
 * can widen access to nineteen people's worth of someone else's unpublished fieldwork in one press,
 * which is not a mistake anyone notices from a green banner.
 *
 * IT IS NOT THE ONLY TRIGGER, AND THE OTHER ONE IGNORES THE COUNT ENTIRELY. `submitGrant` also
 * confirms whenever the action would REMOVE access somebody already holds, one colleague or twenty
 * — see `reductions`. The paragraph above argues that a grant to one person is cheap because Revoke
 * undoes it, and that argument holds only for a grant that ADDS: `_upsert_grant` reconciles the
 * scope to exactly what was sent, so a one-person save that drops eleven records from a colleague's
 * subset has destroyed a list the owner would have to rebuild by hand, and there is no undo for it.
 */
const BULK_CONFIRM_AT = 2;

/**
 * How many records a removal warning names before it collapses to a count.
 *
 * Deliberately not the six `nameList` defaults to for PEOPLE (`NAMES_IN_PROSE` in
 * `lib/sharingScope.ts`): a person's name is short, and a record's label carries its type as well
 * ("Product · Blue lassi jar"), so six of those in one sentence is a paragraph. Four is enough to
 * recognise the list; the count that follows is what makes the rest of it honest.
 */
const REMOVALS_IN_PROSE = 4;

const STATUS_STYLE: Record<string, string> = {
  PENDING: "bg-amber-100 text-amber-800",
  GRANTED: "bg-emerald-100 text-emerald-800",
  DENIED: "bg-red-100 text-red-700",
  REVOKED: "bg-line-200 text-ink-700"
};

function StatusPill({ status }: { status: string }) {
  return <span className={`rounded-full px-2 py-0.5 text-xs font-semibold ${STATUS_STYLE[status] ?? "bg-line-200"}`}>{status}</span>;
}

function tierAtLeast(tier: DataAccessTier, min: DataAccessTier) {
  return TIER_RANK[tier] >= TIER_RANK[min];
}

// --------------------------------------------------------------------------- existing standing
//
// "Do not offer a person who already holds a grant as though they were new." The picker suffixes
// below turn the grant rows the page already loads into a one-line statement of where each person
// stands, so an owner reading the picker can see what they are CHANGING rather than only what they
// are choosing. The arithmetic behind them — `covers`, `classifyChange`, `scopeRemoval` — lives in
// `lib/sharingScope.ts` so it can be tested without a browser; only the wording is here.

type Change = ReturnType<typeof classifyChange>;

/**
 * The tail of a picker row, for people I might grant access to.
 *
 * Kept short on purpose. The panel truncates a label that outruns its width, and the longest name
 * and address in this deployment already spend fifty characters before this suffix starts — so the
 * state has to survive in a handful of words or it is the part that gets cut off.
 */
function grantSuffix(standing: Standing | undefined, change: Change) {
  if (!standing) return "";
  switch (standing.status) {
    case "GRANTED":
      return change === "same"
        ? ` — ${standing.tier}, ${scopeWords(standing)} · no change`
        : ` — has ${standing.tier}, ${scopeWords(standing)}`;
    case "PENDING":
      return ` — asked for ${standing.tier}`;
    case "DENIED":
      return ` — you denied ${standing.tier}`;
    default:
      return ` — ${standing.tier} revoked`;
  }
}

/** The same tail for people I might request access FROM, worded from the other side. */
function requestSuffix(standing: Standing | undefined) {
  if (!standing) return "";
  switch (standing.status) {
    case "GRANTED":
      return ` — you already have ${standing.tier}`;
    case "PENDING":
      return " — request pending";
    case "DENIED":
      return " — previously denied";
    default:
      return " — your access was revoked";
  }
}

// --------------------------------------------------------------------------- the batch report

/** The single-person call this batch replays, held so Retry cannot drift from what was pressed. */
type Attempt =
  | { kind: "grant"; tier: DataAccessTier; allData: boolean; scopeItems: Array<{ recordType: string; recordId: string }> }
  | { kind: "request"; tier: DataAccessTier; requestNote?: string };

type Ledger = { attempt: Attempt; intent: string; succeeded: BatchOutcome[]; failed: BatchOutcome[] };

/**
 * Who worked, who did not, and why — shown only when something failed.
 *
 * A batch that goes through completely says so in the ordinary green banner and leaves the tables
 * below to name the people; a report would be noise. A batch that half-worked cannot be summarised
 * by either banner, because "Access granted" and "Action failed" are both lies about it.
 */
function BatchReport({ ledger, busy, onRetry, onDismiss }: { ledger: Ledger; busy: boolean; onRetry: () => void; onDismiss: () => void }) {
  const { succeeded, failed } = ledger;
  const noun = ledger.attempt.kind === "grant" ? "granted" : "sent";
  const total = succeeded.length + failed.length;

  return (
    <div className="mt-4 overflow-hidden rounded-md border border-amber-500/40">
      {/* The small status pills on this page keep a fixed light palette in both themes, but a band
          this wide would be a slab of cream across a dark screen, so it is tinted instead. */}
      <div className="flex items-start gap-2 bg-amber-100 px-3 py-2 text-amber-800 dark:bg-amber-500/15 dark:text-amber-100">
        <AlertTriangle className="mt-0.5 h-4 w-4 shrink-0" aria-hidden />
        <div className="min-w-0 flex-1">
          <p className="text-sm font-semibold">
            {succeeded.length} of {total} {noun}.
          </p>
          <p className="text-xs">{ledger.intent}</p>
        </div>
        <button
          type="button"
          onClick={onDismiss}
          aria-label="Dismiss this report"
          className="-mr-1 shrink-0 rounded p-1 text-amber-800 transition hover:bg-amber-500/20 dark:text-amber-100"
        >
          <X className="h-4 w-4" aria-hidden />
        </button>
      </div>
      <div className="bg-card p-3 text-sm">
        {succeeded.length ? (
          <>
            <p className="field-label">{ledger.attempt.kind === "grant" ? "Granted" : "Request sent"}</p>
            <ul className="mt-1 grid gap-1">
              {succeeded.map((row) => (
                <li key={row.id} className="flex items-start gap-2 text-ink-700">
                  <Check className="mt-0.5 h-4 w-4 shrink-0 text-emerald-700 dark:text-emerald-400" aria-hidden />
                  <span className="min-w-0">{row.label}</span>
                </li>
              ))}
            </ul>
          </>
        ) : null}
        <p className={`field-label ${succeeded.length ? "mt-3" : ""}`}>
          {ledger.attempt.kind === "grant" ? "Not granted" : "Not sent"}
        </p>
        <ul className="mt-1 grid gap-1">
          {failed.map((row) => (
            <li key={row.id} className="flex items-start gap-2 text-ink-700">
              <X className="mt-0.5 h-4 w-4 shrink-0 text-error-600 dark:text-red-400" aria-hidden />
              <span className="min-w-0">
                <span className="font-medium text-ink-900">{row.label}</span> — {row.error}
                {row.status ? <span className="text-ink-500"> (HTTP {row.status})</span> : null}
              </span>
            </li>
          ))}
        </ul>
        <div className="mt-3 flex flex-wrap items-center gap-3">
          <button className="field-button" disabled={busy} onClick={onRetry}>
            Retry {failed.length === 1 ? "the one that failed" : `the ${failed.length} that failed`}
          </button>
          {succeeded.length ? (
            // Said out loud because the reader's actual worry about a Retry button is that it will
            // do the successful half twice. It cannot: the server keeps one row per person and
            // rewrites it in place, and this retries only the failures anyway.
            <p className="min-w-0 flex-1 text-xs text-ink-500">
              The {succeeded.length} above {succeeded.length === 1 ? "keeps its" : "keep their"} access — retrying
              re-sends only the {failed.length} that failed, and cannot duplicate anything.
            </p>
          ) : null}
        </div>
      </div>
    </div>
  );
}

export default function SharingPage() {
  const confirm = useConfirm();
  const { user: currentUser } = useAuth();
  const [grants, setGrants] = useState<MyGrants | null>(null);
  const [tiers, setTiers] = useState<TierInfo[]>([]);
  const [users, setUsers] = useState<User[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [message, setMessage] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  // Carries WHICH of the two forms is running, so the progress line appears under the button that
  // was pressed rather than under both of them.
  const [progress, setProgress] = useState<{ kind: Attempt["kind"]; done: number; total: number } | null>(null);
  const [ledger, setLedger] = useState<Ledger | null>(null);

  // Request form state
  const [reqOwnerIds, setReqOwnerIds] = useState<string[]>([]);
  const [reqOwnerText, setReqOwnerText] = useState("");
  const [reqTier, setReqTier] = useState<DataAccessTier>("DOWNLOAD");
  const [reqNote, setReqNote] = useState("");

  // Direct-grant form state (owner grants colleagues access to all, or a chosen subset, of their data)
  const [grantGranteeIds, setGrantGranteeIds] = useState<string[]>([]);
  const [grantTier, setGrantTier] = useState<DataAccessTier>("DOWNLOAD");
  const [grantScopeAll, setGrantScopeAll] = useState(true);
  const [myRecords, setMyRecords] = useState<RecordGroup[] | null>(null);
  const [loadingRecords, setLoadingRecords] = useState(false);
  const [selectedKeys, setSelectedKeys] = useState<Set<string>>(new Set());

  async function loadMyRecords() {
    if (myRecords || loadingRecords || !currentUser?.id) return;
    setLoadingRecords(true);
    const mine = currentUser.id;
    /*
      OWNERSHIP IS ASKED FOR, NOT SIFTED FOR — the same rule /activity already documents.

      This used to fetch page one of every list and filter it on `createdById`. Reading the
      repository is open (`services/records.viewable_where` returns {}), so page one is the newest
      hundred rows of the WHOLE archive: MEASURED against the running API there are 431 artisans and
      613 products, with page one of /artisans spanning 34 distinct creators. An owner could
      therefore only ever offer the records that happened to sit in that hundred, so a subset grant
      over their older work was impossible to build and nothing on screen said why.

      The symptom that makes it concrete, measured against the same API: page one of each list held
      NONE of the signed-in user's records, so the picker read "You have no records to share" to a
      designer who owned plenty.

      The client-side filter below is kept as well: it costs nothing, and against a deployment that
      ignores the parameter it is the difference between an over-long list and a wrong one.

      MEDIA IS ASKED THE SAME QUESTION UNDER A DIFFERENT NAME. `MediaFile` owns its rows through
      `uploadedById`, not `createdById`, and `GET /media` spells the parameter `uploadedBy` to match
      — the query key follows the column on both sides of the wire, exactly as /activity does it.

      `allSettled`, NOT `all`. Eight lists now, and `Promise.all` rejects on the first failure: one
      list refusing took the whole picker down and reported "Unable to load your records", which on
      this screen reads as "you have none". Each type now stands or falls alone and says which.
    */
    const owned = { pageSize: PICKER_PAGE_SIZE, createdBy: mine };
    const [a, p, t, w, q, c, pr, m] = await Promise.allSettled([
      listResource<Artisan>("/artisans", owned),
      listResource<ProductDocumentation>("/products", owned),
      listResource<ToolDocumentation>("/tools", owned),
      listResource<Workshop>("/workshops", owned),
      listResource<QuestionnaireInterview>("/questionnaire/interviews", owned),
      listResource<Craft & { createdById?: string | null }>("/crafts", owned),
      listResource<ProcessRecord>("/processes", owned),
      listResource<MediaFile & { uploadedById?: string | null }>("/media", {
        pageSize: PICKER_PAGE_SIZE,
        uploadedBy: mine
      })
    ]);

    /**
     * One type's rows, its true count, and its failure — all three, because leaving any one of them
     * out turns this list into the "quietly stopped" shape rule 10 is about.
     */
    function group<T extends { id: string }>(
      recordType: string,
      heading: string,
      noun: string,
      reach: string | null,
      result: PromiseSettledResult<PageResult<T>>,
      isMine: (row: T) => boolean,
      name: (row: T) => string
    ): RecordGroup {
      if (result.status === "rejected") {
        const reason = result.reason;
        return {
          recordType,
          heading,
          noun,
          reach,
          records: [],
          total: null,
          fetched: 0,
          failed: reason instanceof Error ? reason.message : "could not be loaded"
        };
      }
      const page = result.value;
      // `apiFetch` casts a non-JSON body to the caller's type, so a proxy's HTML error page arrives
      // here typed as a `PageResult` with no `items` at all. Treated as an empty, UNCOUNTED page
      // rather than allowed to throw: a throw out of this loop would leave the picker on "Loading
      // your records…" for the rest of the session, which is the one state that says nothing.
      const items = Array.isArray(page?.items) ? page.items : [];
      return {
        recordType,
        heading,
        noun,
        reach,
        records: items.filter(isMine).map((row) => ({ recordType, recordId: row.id, name: name(row) || row.id })),
        total: typeof page?.total === "number" ? page.total : null,
        fetched: items.length,
        failed: null
      };
    }

    setMyRecords([
      // The five the download filter honours first, then the three it does not — so the note under
      // the list about where each type stops reads in the same order as the list itself.
      group("artisan", "Artisans", "Artisan", RECORD_REACH.full, a, (x) => x.createdById === mine, (x) => x.name),
      group("product", "Products", "Product", RECORD_REACH.full, p, (x) => x.createdById === mine, (x) => x.productName),
      group("tool", "Tools", "Tool", RECORD_REACH.full, t, (x) => x.createdById === mine, (x) => x.toolkitName),
      group("workshop", "Workshops", "Workshop", RECORD_REACH.full, w, (x) => x.createdById === mine, (x) => x.title),
      // "questionnaire", not "questionnaireinterview": both resolve through `RECORD_DELEGATES`, but
      // `export.py`'s subset filter tests the short spelling, so the long one would store a scope
      // item that grants comment and edit and is invisible to the download.
      group("questionnaire", "Interviews", "Interview", RECORD_REACH.full, q, (x) => x.createdById === mine, (x) => x.title),
      group("craft", "Crafts", "Craft", RECORD_REACH.craft, c, (x) => x.createdById === mine, (x) => x.name),
      group("process", "Processes", "Process", RECORD_REACH.process, pr, (x) => x.createdById === mine, (x) => x.name),
      group(
        "media",
        "Files",
        "File",
        RECORD_REACH.media,
        m,
        (x) => (x.uploadedById ?? x.uploadedBy?.id) === mine,
        (x) => x.caption?.trim() || x.originalFilename
      )
    ]);
    setLoadingRecords(false);
  }

  function toggleRecord(key: string) {
    setSelectedKeys((prev) => {
      const next = new Set(prev);
      if (next.has(key)) next.delete(key);
      else next.add(key);
      return next;
    });
  }

  const load = useCallback(async () => {
    try {
      const [g, t, u] = await Promise.all([
        apiFetch<MyGrants>("/data-access/grants"),
        apiFetch<TierInfo[]>("/data-access/tiers"),
        apiFetch<User[]>("/users/directory").catch(() => [] as User[])
      ]);
      setGrants(g);
      setTiers(t);
      setUsers((u ?? []).filter((x) => x.id !== currentUser?.id));
      setError(null);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Unable to load sharing data");
    }
  }, [currentUser?.id]);

  useEffect(() => {
    load();
  }, [load]);

  // Researchers can't list /users (admin-only). Fall back to a free-text owner id only if needed.
  const canPickUsers = users.length > 0;

  const ownerNameById = useMemo(() => {
    const map = new Map<string, string>();
    users.forEach((u) => map.set(u.id, `${u.name} (${u.email})`));
    return map;
  }, [users]);

  /** Just the person's name, for prose. The picker rows carry the address; a sentence should not. */
  const plainNameById = useMemo(() => {
    const map = new Map<string, string>();
    users.forEach((u) => map.set(u.id, u.name));
    return map;
  }, [users]);

  const incoming = useMemo(() => grants?.incoming ?? [], [grants]);
  const outgoing = useMemo(() => grants?.outgoing ?? [], [grants]);

  const granteeStandings = useMemo(() => standingsBy(incoming, "granteeId"), [incoming]);
  const ownerStandings = useMemo(() => standingsBy(outgoing, "ownerId"), [outgoing]);

  const nextScope: Scope = useMemo(
    () => ({ allData: grantScopeAll, keys: selectedKeys }),
    [grantScopeAll, selectedKeys]
  );

  /**
   * The colleague picker's rows, each carrying what that person holds today.
   *
   * Recomputed against the current tier and scope so "no change" means no change to what is on
   * screen right now, not to some earlier draft of the action.
   */
  const grantOptions: SelectOption[] = useMemo(
    () =>
      users.map((u) => {
        const standing = granteeStandings.get(u.id);
        return {
          value: u.id,
          label: `${u.name} · ${u.email}${grantSuffix(standing, classifyChange(standing, { ...nextScope, tier: grantTier }))}`
        };
      }),
    [users, granteeStandings, nextScope, grantTier]
  );

  /**
   * The researcher picker's rows.
   *
   * Anyone whose data I already hold an active grant on is DISABLED rather than merely annotated,
   * because the server refuses that request outright — `request_access` 409s on an existing GRANTED
   * row so that re-asking cannot knock a working grant back to PENDING. Offering the row anyway
   * would mean "Select all" reliably produced failures nobody could have avoided; disabled rows are
   * also skipped by the panel's own select-all, so the bulk action stays clean by construction.
   */
  const requestOptions: SelectOption[] = useMemo(
    () =>
      users.map((u) => {
        const standing = ownerStandings.get(u.id);
        return {
          value: u.id,
          label: `${u.name} · ${u.email}${requestSuffix(standing)}`,
          disabled: standing?.status === "GRANTED"
        };
      }),
    [users, ownerStandings]
  );

  /**
   * Every record this picker can put a NAME to, keyed exactly as a scope item is.
   *
   * Built from the loaded groups, so it holds only what the picker actually fetched — which is the
   * point: a scope item this map cannot resolve is a record the owner is about to remove and cannot
   * see, and `reductions` counts those separately rather than pretending they are not there.
   */
  const recordNameByKey = useMemo(() => {
    const map = new Map<string, string>();
    (myRecords ?? []).forEach((group) =>
      group.records.forEach((record) =>
        map.set(scopeKey(record.recordType, record.recordId), `${group.noun} · ${record.name}`)
      )
    );
    return map;
  }, [myRecords]);

  /**
   * WHAT THIS PICKER IS NOT SHOWING — the two ways a subset grant built here can be short.
   *
   * RULE 10, AND ON THIS SCREEN IT IS NOT MERELY A DISPLAY RULE. A record the picker cannot show is
   * a record that cannot be ticked; and because `_upsert_grant` reconciles a grant's scope to
   * exactly what is sent, an untickable record that is already in a colleague's grant is REMOVED by
   * the next save. So a list that quietly stopped at a hundred rows would not just under-offer —
   * it would silently destroy the part of somebody's access that lives past the cap.
   */
  const recordShortfalls = useMemo(() => {
    const groups = myRecords ?? [];
    return {
      capped: groups.filter((group) => group.total !== null && group.total > group.fetched),
      failed: groups.filter((group) => group.failed)
    };
  }, [myRecords]);

  /**
   * Colleagues in the current selection whose existing access this action would cut back — AND WHAT
   * EXACTLY IT WOULD TAKE FROM EACH OF THEM.
   *
   * The warning used to say only that access would be lowered and how much of it there was ("EDIT,
   * 12 records"), which an owner cannot check anything against. `_upsert_grant` reconciles the scope
   * to exactly what is sent, so the honest question is "which twelve", and it is answerable: the
   * held keys minus the ticked ones. What is not always answerable is their NAMES — a record beyond
   * this picker's 100-row page, or one of a type this picker does not list, has a key and no label —
   * so those are COUNTED, never dropped. See {@link ScopeRemoval} for the all-data case, which has
   * no list to give at all.
   */
  const reductions = useMemo(
    () =>
      grantGranteeIds
        .map((id) => ({ id, standing: granteeStandings.get(id) }))
        .filter(
          (entry): entry is { id: string; standing: Standing } =>
            Boolean(entry.standing) && classifyChange(entry.standing, { ...nextScope, tier: grantTier }) === "reduce"
        )
        .map(({ id, standing }) => {
          const removal = scopeRemoval(standing, nextScope);
          const removedKeys = removal.kind === "records" ? removal.keys : [];
          const named = removedKeys.map((key) => recordNameByKey.get(key)).filter((label): label is string => Boolean(label));
          return {
            id,
            name: plainNameById.get(id) ?? id,
            held: `${standing.tier}, ${scopeWords(standing)}`,
            /** Set when the TIER itself drops, which is a reduction even when the scope is unchanged. */
            tierFrom: TIER_RANK[grantTier] < TIER_RANK[standing.tier] ? standing.tier : null,
            /** They hold everything and the new scope is a subset — see {@link ScopeRemoval}. */
            losesAllData: removal.kind === "allData",
            named,
            /** Removed records this page cannot name. Counted so the sentence is not short by them. */
            unnamed: removedKeys.length - named.length
          };
        }),
    [grantGranteeIds, granteeStandings, nextScope, grantTier, plainNameById, recordNameByKey]
  );

  /**
   * One reduction as a sentence. Kept out of JSX because it is a judgement about truncation, and the
   * banner and the confirm dialog must not be able to describe one save two different ways.
   */
  function removalSentence(entry: (typeof reductions)[number]): string {
    const parts: string[] = [];
    if (entry.tierFrom) parts.push(`drops from ${entry.tierFrom} to ${grantTier}`);
    if (entry.losesAllData) {
      parts.push(`loses everything except the ${plural(selectedKeys.size, "record")} you ticked`);
    } else if (entry.named.length || entry.unnamed) {
      // "; plus", not a second "and": `nameList` may already have spent one collapsing its own tail,
      // and "…and 2 others and 3 records this page cannot list" reads as one list, not two facts.
      const list = nameList(entry.named, REMOVALS_IN_PROSE);
      const tail = entry.unnamed
        ? `${entry.named.length ? "; plus " : ""}${plural(entry.unnamed, "record")} this page cannot list`
        : "";
      parts.push(`loses ${list}${tail}`);
    }
    // A reduce with neither half can only mean a tier drop the branch above already caught; say
    // something rather than print a name followed by nothing.
    if (!parts.length) parts.push("has their grant replaced");
    return `${entry.name} (has ${entry.held}) ${parts.join(", and ")}.`;
  }

  const grantScopePhrase = grantScopeAll ? "all your data" : `${plural(selectedKeys.size, "selected record")}`;

  /**
   * The sentence to check before pressing. One scope and one tier apply to EVERY person chosen, and
   * that is exactly the thing a row of separate controls does not say out loud.
   */
  const grantSentence =
    grantGranteeIds.length === 0
      ? "Choose one or more colleagues, then press Grant."
      : `Grant ${grantTier} on ${grantScopePhrase} to ${
          grantGranteeIds.length === 1
            ? plainNameById.get(grantGranteeIds[0]) ?? "1 colleague"
            : plural(grantGranteeIds.length, "colleague")
        }.`;

  const requestCount = canPickUsers ? reqOwnerIds.length : reqOwnerText.trim() ? 1 : 0;
  const requestSentence =
    requestCount === 0
      ? "Choose one or more researchers, then press Request."
      : `Request ${reqTier} access to all data from ${
          requestCount === 1
            ? (canPickUsers ? plainNameById.get(reqOwnerIds[0]) : null) ?? "1 researcher"
            : plural(requestCount, "researcher")
        }.`;

  async function act<T>(fn: () => Promise<T>, ok: string) {
    setBusy(true);
    setError(null);
    setMessage(null);
    try {
      await fn();
      setMessage(ok);
      await load();
    } catch (err) {
      setError(err instanceof Error ? err.message : "Action failed");
    } finally {
      setBusy(false);
    }
  }

  /**
   * The fan-out: one POST per person, no rollback, a named reason for each one that fails.
   *
   * `runPerPerson` never throws — a batch always produces a full account — so everything after the
   * await is about telling the truth and leaving the screen in a state the reader can act from. On a
   * partial failure the picker is reset to exactly the people who did NOT go through, so both the
   * Retry button and a second press of Grant mean the same, obvious thing.
   */
  async function runPeople(attempt: Attempt, intent: string, targets: BatchTarget[]) {
    setBusy(true);
    setError(null);
    setMessage(null);
    setLedger(null);
    setProgress({ kind: attempt.kind, done: 0, total: targets.length });

    const result = await runPerPerson(
      targets,
      (target) =>
        attempt.kind === "grant"
          ? apiFetch("/data-access/grants", {
              method: "POST",
              body: JSON.stringify({
                granteeId: target.id,
                tier: attempt.tier,
                allData: attempt.allData,
                scopeItems: attempt.scopeItems
              })
            })
          : apiFetch("/data-access/requests", {
              method: "POST",
              body: JSON.stringify({ ownerId: target.id, tier: attempt.tier, allData: true, requestNote: attempt.requestNote })
            }),
      (done, total) => setProgress({ kind: attempt.kind, done, total })
    );

    setProgress(null);
    // Reload before the messaging, so the tables and the picker's standing labels already agree with
    // whatever the report is about to claim.
    await load();
    setBusy(false);

    const failedIds = result.failed.map((row) => row.id);
    if (attempt.kind === "grant") {
      setGrantGranteeIds(failedIds);
      if (!failedIds.length) {
        setSelectedKeys(new Set());
        setGrantScopeAll(true);
      }
    } else {
      setReqOwnerIds(failedIds);
      if (!failedIds.length) {
        setReqNote("");
        setReqOwnerText("");
      }
    }

    if (!result.failed.length) {
      setMessage(
        attempt.kind === "grant"
          ? `Access granted to ${plural(result.succeeded.length, "colleague")}.`
          : `Sent ${plural(result.succeeded.length, "request")}.`
      );
      return;
    }
    setLedger({ attempt, intent, succeeded: result.succeeded, failed: result.failed });
  }

  function targetsFor(ids: string[]): BatchTarget[] {
    return ids.map((id) => ({ id, label: ownerNameById.get(id) ?? id }));
  }

  async function submitRequest() {
    const ids = canPickUsers ? reqOwnerIds : reqOwnerText.trim() ? [reqOwnerText.trim()] : [];
    if (!ids.length) {
      setError("Choose at least one researcher to request access from.");
      return;
    }
    const names = ids.map((id) => plainNameById.get(id) ?? id);
    // A request exposes nothing, so this is not the permissions warning the grant side gets — it is
    // a guard against one "Select all" quietly putting a request in front of every colleague at once.
    if (ids.length >= BULK_CONFIRM_AT) {
      const ok = await confirm({
        title: `Send ${plural(ids.length, "access request")}?`,
        body: (
          <>
            <span className="font-medium text-ink-900">{nameList(names)}</span> will each be asked for{" "}
            <span className="font-medium text-ink-900">{reqTier}</span> access to all of their data.
          </>
        ),
        note: "Nothing is shared until each of them approves, and you can withdraw any request from the list below.",
        confirmLabel: `Send ${ids.length} requests`
      });
      if (!ok) return;
    }
    await runPeople({ kind: "request", tier: reqTier, requestNote: reqNote.trim() || undefined }, requestSentence, targetsFor(ids));
  }

  async function submitGrant() {
    if (!grantGranteeIds.length) {
      setError("Choose at least one colleague to grant access to.");
      return;
    }
    // `splitScopeKey` and not `k.split("::")`: a cuid never contains the separator, but a two-part
    // destructure of a three-part split drops the tail silently, and the thing being dropped here is
    // half of a record's identity.
    const scopeItems = grantScopeAll ? [] : Array.from(selectedKeys).map(splitScopeKey);
    if (!grantScopeAll && scopeItems.length === 0) {
      setError("Pick at least one record to share, or choose All my data.");
      return;
    }
    const names = grantGranteeIds.map((id) => plainNameById.get(id) ?? id);
    /*
      TWO TRIGGERS, AND THE SECOND ONE IGNORES THE COUNT. `BULK_CONFIRM_AT` is about breadth — one
      press reaching many people. This second clause is about DESTRUCTION, which one colleague is
      quite enough for: `_upsert_grant` reconciles the scope to exactly what is sent, so a save that
      drops eleven records out of somebody's subset has destroyed a list the owner would have to
      rebuild by hand, and no Revoke undoes that. The banner above the button already says so while
      the selection is being built; this is the last place it can be said before it happens.
    */
    if (grantGranteeIds.length >= BULK_CONFIRM_AT || reductions.length > 0) {
      // Red, not amber, in the two cases that are not merely consequential: handing several people
      // the maximum tier over everything, and any action that destroys access somebody already has.
      const severe = reductions.length > 0 || (grantTier === "EDIT" && grantScopeAll);
      const ok = await confirm({
        title: `Grant ${grantTier} to ${plural(grantGranteeIds.length, "colleague")}?`,
        body: (
          <>
            <span className="font-medium text-ink-900">{nameList(names)}</span> will be able to{" "}
            {TIER_CONSEQUENCE[grantTier]} <span className="font-medium text-ink-900">{grantScopePhrase}</span> straight
            away — a direct grant has no approval step.
          </>
        ),
        note: (
          <>
            {reductions.length ? (
              // NAMED, ONE PERSON PER LINE, and the records they lose spelled out. A comma-run of
              // "Priya (EDIT, 12 records), Anil (COMMENT, 4 records)" told an owner that something
              // was being taken and never what — which is not a fact anyone can check in the two
              // seconds a confirm dialog is open.
              <span className="block font-medium text-ink-900">
                This TAKES ACCESS AWAY from {reductions.length === 1 ? "someone" : `${reductions.length} of them`}. One
                tier and one scope apply to everyone chosen, so an existing grant is replaced, not added to:
                <span className="mt-1 block font-normal">
                  {reductions.map((r) => (
                    <span key={r.id} className="block">
                      {removalSentence(r)}
                    </span>
                  ))}
                </span>
              </span>
            ) : null}
            <span className="block">You can revoke any of them individually from the list below at any time.</span>
          </>
        ),
        tone: severe ? "danger" : "warning",
        confirmLabel: `Grant to ${grantGranteeIds.length}`
      });
      if (!ok) return;
    }
    await runPeople({ kind: "grant", tier: grantTier, allData: grantScopeAll, scopeItems }, grantSentence, targetsFor(grantGranteeIds));
  }

  function retryLedger() {
    if (!ledger) return;
    runPeople(ledger.attempt, ledger.intent, ledger.failed.map((row) => ({ id: row.id, label: row.label })));
  }

  async function decide(grant: DataAccessGrant, status: "GRANTED" | "DENIED", tier?: DataAccessTier) {
    const who = grant.grantee?.name ?? grant.granteeId;
    // Denying is reversible — the requester can ask again, and the owner can grant later — so this is
    // amber rather than red. Granting needs no confirmation at all.
    if (status === "DENIED") {
      const ok = await confirm({
        title: "Deny this request?",
        body: (
          <>
            <span className="font-medium text-ink-900">{who}</span> will not get access to your data, and will see the
            request as denied.
          </>
        ),
        note: "They can request access again, and you can grant it at any time.",
        tone: "warning",
        confirmLabel: "Deny request"
      });
      if (!ok) return;
    }
    await act(
      () =>
        apiFetch(`/data-access/grants/${grant.id}/decide`, {
          method: "POST",
          body: JSON.stringify({ status, tier: tier ?? grant.tier })
        }),
      status === "GRANTED" ? "Access granted." : "Request denied."
    );
  }

  async function changeTier(grant: DataAccessGrant, tier: DataAccessTier) {
    await act(() => apiFetch(`/data-access/grants/${grant.id}`, { method: "PATCH", body: JSON.stringify({ tier }) }), "Tier updated.");
  }

  async function revoke(grant: DataAccessGrant) {
    const who = grant.grantee?.name ?? grant.granteeId;
    const ok = await confirm({
      title: "Revoke this access?",
      body: (
        <>
          <span className="font-medium text-ink-900">{who}</span> loses access to your data immediately, including
          anything they were part-way through downloading.
        </>
      ),
      note: "Comments and edits they already made are kept.",
      tone: "danger",
      confirmLabel: "Revoke access"
    });
    if (!ok) return;
    await act(() => apiFetch(`/data-access/grants/${grant.id}/revoke`, { method: "POST" }), "Access revoked.");
  }

  // Destructive on both sides of the table: as owner it deletes a denied/revoked row, as grantee it
  // withdraws a pending request or drops access already held. Confirm before it fires.
  async function remove(grant: DataAccessGrant) {
    const ok = await confirm({
      ...deleteConfirm(
        "Remove this sharing entry?",
        "This permanently deletes the grant record, along with the history of who asked for what and when.",
        "As the owner this clears a denied or revoked row; as the requester it withdraws the request or drops access you hold."
      ),
      confirmLabel: "Remove entry"
    });
    if (!ok) return;
    await act(() => apiFetch(`/data-access/grants/${grant.id}`, { method: "DELETE" }), "Removed.");
  }

  async function downloadOwnerData(ownerId: string, ownerLabel: string) {
    let capped = false;
    await act(async () => {
      const manifest = await apiFetch<{
        files: Array<{ path: string; url?: string | null; content?: string | null }>;
        totalFiles: number;
        totalMedia: number;
        /** The server hit a per-table row cap: this archive is a prefix of the data, not all of it. */
        truncated?: boolean;
      }>(`/export/dataset?ownerId=${encodeURIComponent(ownerId)}`);
      capped = Boolean(manifest.truncated);
      // Assemble a real zip in the browser: text entries inline, media fetched from storage.
      const { default: JSZip } = await import("jszip");
      const zip = new JSZip();
      const failed: string[] = [];
      let done = 0;
      for (const file of manifest.files) {
        done += 1;
        setMessage(`Preparing download… ${done}/${manifest.files.length}`);
        if (file.content != null) {
          zip.file(file.path, file.content);
          continue;
        }
        if (!file.url) {
          failed.push(file.path);
          continue;
        }
        try {
          const response = await fetch(file.url);
          if (!response.ok) throw new Error(`HTTP ${response.status}`);
          zip.file(file.path, await response.blob());
        } catch {
          failed.push(file.path);
        }
      }
      if (failed.length) {
        zip.file(
          "_failed-downloads.txt",
          `These files could not be fetched and are not in the archive:\n\n${failed.join("\n")}`
        );
      }
      const blob = await zip.generateAsync({ type: "blob" });
      const url = URL.createObjectURL(blob);
      const a = document.createElement("a");
      a.href = url;
      a.download = `data-${ownerLabel.replace(/[^A-Za-z0-9]+/g, "_")}.zip`;
      a.click();
      URL.revokeObjectURL(url);
    }, "Download ready.");
    // Said AFTER `act`, which writes its own success message last. A partial archive that presents
    // itself as complete is worse than a failed one, because nobody goes back for the rest.
    if (capped) {
      setMessage(
        "Download ready — but this export hit the server's row cap, so it does NOT contain all of " +
          `${ownerLabel}'s data. Ask an admin for a full extract.`
      );
    }
  }

  function progressLine(kind: Attempt["kind"]) {
    if (!progress || progress.kind !== kind) return null;
    return `Working… ${progress.done} of ${progress.total} sent.`;
  }

  return (
    <>
      <PageHeader
        title="Sharing"
        description="Request access to another researcher's data, and manage who can use yours — at three tiers."
        icon={<Share2 className="h-5 w-5" aria-hidden />}
      />

      {error ? <div className="mb-4 rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">{error}</div> : null}
      {message ? <div className="mb-4 rounded-md border border-emerald-200 bg-emerald-50 px-3 py-2 text-sm text-emerald-700">{message}</div> : null}

      {/* Tier definitions, shown so a user knows exactly what each tier confers. */}
      <section className="panel mb-5 p-4">
        <h2 className="font-display font-bold text-lg text-ink">Access tiers</h2>
        <ul className="mt-2 grid gap-2 md:grid-cols-3">
          {tiers.map((t) => (
            <li key={t.tier} className="rounded-md border border-line-200 bg-field-50 p-3 text-sm">
              <div className="font-semibold text-ink">{TIER_LABEL[t.tier]}</div>
              <div className="mt-1 text-ink-muted">{t.description}</div>
            </li>
          ))}
        </ul>
      </section>

      {/* Request access from one or more researchers. */}
      <section className="panel mb-5 p-4">
        <h2 className="font-display font-bold text-lg text-ink">Request access to researchers&apos; data</h2>
        <div className="mt-3 grid gap-3 md:grid-cols-[2fr_1.4fr_2fr_auto] md:items-end">
          <Field label="Researchers">
            {canPickUsers ? (
              <SearchableMultiSelect
                values={reqOwnerIds}
                onChange={setReqOwnerIds}
                options={requestOptions}
                // `searchable` on both people pickers on this page. They are account lists, so the
                // number of rows is a fact about the deployment; and this is a PERMISSIONS form,
                // where picking the wrong name is the mistake that costs something. Typing a name is
                // the only way to be sure, and it must not depend on how many colleagues exist.
                searchable
                placeholder="Select…"
                ariaLabel="Researchers to request access from"
                disabled={busy}
                // No Confirm button inside the panel: the real commit is the Request button sitting
                // beside it, and two buttons that both look like "done" on a permissions form is how
                // someone sends the wrong thing. Clicking Request closes the panel and fires in one
                // press, so single-person use is no slower than the old single select.
                confirmOnSelect={false}
              />
            ) : (
              <TextInput value={reqOwnerText} onChange={(e) => setReqOwnerText(e.target.value)} placeholder="Researcher user id" />
            )}
          </Field>
          {/* Single-select on purpose: the tiers are a ladder, not a set. See TIER_RANK. And no
              `searchable` on any of the three tier pickers on this page: three rungs, spelled out in
              TIER_LABEL, which is a list you read rather than search. */}
          <Field label="Tier">
            <Select value={reqTier} onChange={(e) => setReqTier(e.target.value as DataAccessTier)}>
              <option value="DOWNLOAD">{TIER_LABEL.DOWNLOAD}</option>
              <option value="COMMENT">{TIER_LABEL.COMMENT}</option>
              <option value="EDIT">{TIER_LABEL.EDIT}</option>
            </Select>
          </Field>
          <Field label="Note (optional)">
            <TextInput value={reqNote} onChange={(e) => setReqNote(e.target.value)} placeholder="Why you need access" />
          </Field>
          <button className="field-button" disabled={busy} onClick={submitRequest}>
            Request
          </button>
        </div>
        {/* Below the button row, never above it: this block grows and shrinks with the selection, and
            a control that moves between the press and the release is a control that misses. */}
        <p className="mt-3 rounded-md border border-line-200 bg-field-50 px-3 py-2 text-sm font-medium text-ink-900">
          {progressLine("request") ?? requestSentence}
        </p>
        <p className="mt-2 text-xs text-ink-muted">
          One tier applies to every researcher chosen. Requests cover all of that researcher&apos;s data — the owner can
          narrow it to a subset when they approve.
        </p>
        {ledger?.attempt.kind === "request" ? (
          <BatchReport ledger={ledger} busy={busy} onRetry={retryLedger} onDismiss={() => setLedger(null)} />
        ) : null}
      </section>

      {/* Grant access directly — owner shares all, or a chosen subset, of their own data. */}
      <section className="panel mb-5 p-4">
        <h2 className="font-display font-bold text-lg text-ink">Grant access to your data</h2>
        <div className="mt-3 grid gap-3 md:grid-cols-[2fr_1.4fr_auto] md:items-end">
          <Field label="Colleagues">
            <SearchableMultiSelect
              values={grantGranteeIds}
              onChange={setGrantGranteeIds}
              options={grantOptions}
              searchable
              placeholder="Select…"
              ariaLabel="Colleagues to grant access to"
              disabled={busy}
              confirmOnSelect={false}
            />
          </Field>
          {/* Single-select on purpose: the tiers are a ladder, not a set. See TIER_RANK. */}
          <Field label="Tier">
            <Select value={grantTier} onChange={(e) => setGrantTier(e.target.value as DataAccessTier)}>
              <option value="DOWNLOAD">{TIER_LABEL.DOWNLOAD}</option>
              <option value="COMMENT">{TIER_LABEL.COMMENT}</option>
              <option value="EDIT">{TIER_LABEL.EDIT}</option>
            </Select>
          </Field>
          <button className="field-button" disabled={busy} onClick={submitGrant}>
            Grant
          </button>
        </div>
        <p className="mt-3 rounded-md border border-line-200 bg-field-50 px-3 py-2 text-sm font-medium text-ink-900">
          {progressLine("grant") ?? grantSentence}
        </p>
        {reductions.length ? (
          <p className="mt-2 flex items-start gap-2 rounded-md border border-amber-500/40 bg-amber-100 px-3 py-2 text-xs text-amber-800 dark:bg-amber-500/15 dark:text-amber-100">
            <AlertTriangle className="mt-0.5 h-4 w-4 shrink-0" aria-hidden />
            {/*
              WHAT WOULD GO, NOT JUST THAT SOMETHING WOULD. This banner is on screen while the
              selection is still being built, which is the only moment an owner can still change
              their mind cheaply — so it names the records, and where it cannot name them it says how
              many it could not. A save REPLACES a grant's scope; this is the sentence that makes
              that fact checkable rather than merely stated.
            */}
            <span>
              This would TAKE ACCESS AWAY from{" "}
              {reductions.length === 1 ? "one of them" : `${reductions.length} of them`}. A grant replaces an existing one
              rather than adding to it:
              <span className="mt-1 block">
                {reductions.map((r) => (
                  <span key={r.id} className="block">
                    {removalSentence(r)}
                  </span>
                ))}
              </span>
            </span>
          </p>
        ) : null}
        <div className="mt-3 flex flex-wrap gap-4 text-sm">
          <label className="flex items-center gap-2">
            <input
              type="radio"
              name="grantScope"
              checked={grantScopeAll}
              onChange={() => setGrantScopeAll(true)}
            />
            All my data
          </label>
          <label className="flex items-center gap-2">
            <input
              type="radio"
              name="grantScope"
              checked={!grantScopeAll}
              onChange={() => {
                setGrantScopeAll(false);
                loadMyRecords();
              }}
            />
            Only selected records
          </label>
        </div>
        {!grantScopeAll ? (
          <>
            {/*
              GROUPED BY TYPE, because eight types at up to a hundred rows apiece is eight hundred
              checkboxes in one scroll box and there is no filter over it — deliberately: these lists
              are SERVER-TRUNCATED at the API's largest page, and a client-side filter over a
              truncated list answers "no matches" about records that exist, which is rule 10 wearing
              a search box (SKILL.md §11.5). The heading is what makes each type findable, and it is
              also where that type's real numbers go.
            */}
            <div className="mt-2 max-h-64 overflow-y-auto rounded-md border border-line-200 bg-field-50 p-2">
              {loadingRecords || !myRecords ? (
                <p className="px-2 py-1 text-sm text-ink-muted">Loading your records…</p>
              ) : myRecords.every((group) => !group.records.length && !group.failed) ? (
                <p className="px-2 py-1 text-sm text-ink-muted">You have no records to share.</p>
              ) : (
                myRecords.map((group) => {
                  // A type with nothing in it and no failure has nothing to say; a type that FAILED
                  // is rendered precisely because it has something to say.
                  if (!group.records.length && !group.failed) return null;
                  const capped = group.total !== null && group.total > group.fetched;
                  return (
                    <div key={group.recordType} className="mb-2 last:mb-0">
                      <p className="px-2 pb-1 pt-1 text-xs font-semibold uppercase tracking-wide text-ink-500">
                        {/*
                          THE TICKABLE COUNT FIRST, THE TRUE COUNT AFTER IT. "100 shown" is a fact
                          about the rows under this heading and "431 in all" is a fact about the
                          query, and they are printed as two numbers rather than one ratio because
                          on a deployment that ignores `createdBy` the client filter narrows the
                          first and not the second — a single "100 of 431" would then be describing
                          two different populations in one phrase.
                        */}
                        {group.heading}
                        {group.failed
                          ? " · list unavailable"
                          : capped
                            ? ` · ${group.records.length} shown, ${group.total} in all`
                            : ` · ${group.records.length}`}
                      </p>
                      {group.failed ? (
                        <p className="px-2 pb-1 text-xs text-amber-800 dark:text-amber-100">
                          {group.failed} — records of this type cannot be ticked, and any already in a colleague&apos;s
                          grant will be removed by a save made from here.
                        </p>
                      ) : null}
                      {group.reach ? <p className="px-2 pb-1 text-xs text-ink-muted">{group.reach}</p> : null}
                      {group.records.map((record) => {
                        const key = scopeKey(record.recordType, record.recordId);
                        return (
                          <label key={key} className="flex items-center gap-2 rounded px-2 py-1 hover:bg-field-100">
                            <input type="checkbox" checked={selectedKeys.has(key)} onChange={() => toggleRecord(key)} />
                            <span className="min-w-0 flex-1 truncate text-sm text-ink">{record.name}</span>
                          </label>
                        );
                      })}
                    </div>
                  );
                })
              )}
            </div>
            {/*
              THE CAP, SAID ONCE MORE OUTSIDE THE SCROLL BOX — because the headings inside it scroll
              away, and this is the sentence that connects the cap to the damage rather than merely
              reporting it.
            */}
            {recordShortfalls.capped.length || recordShortfalls.failed.length ? (
              <p className="mt-2 flex items-start gap-2 rounded-md border border-amber-500/40 bg-amber-100 px-3 py-2 text-xs leading-5 text-amber-800 dark:bg-amber-500/15 dark:text-amber-100">
                <AlertTriangle className="mt-0.5 h-4 w-4 shrink-0" aria-hidden />
                <span>
                  {recordShortfalls.capped.length ? (
                    <span className="block">
                      This list stops at the first {PICKER_PAGE_SIZE} rows per type — the largest page the API answers.
                      Not shown:{" "}
                      {recordShortfalls.capped
                        .map((group) => `${(group.total ?? 0) - group.fetched} more ${group.heading.toLowerCase()}`)
                        .join(", ")}
                      .
                    </span>
                  ) : null}
                  {recordShortfalls.failed.length ? (
                    <span className="block">
                      {nameList(recordShortfalls.failed.map((group) => group.heading))} could not be listed at all.
                    </span>
                  ) : null}
                  <span className="block font-medium">
                    A record that is not on this list cannot be ticked — and a save REPLACES a colleague&apos;s whole
                    scope, so anything of theirs that lives past the cap is removed by it. Use{" "}
                    <span className="font-semibold">All my data</span> if you cannot see everything you mean to keep.
                  </span>
                </span>
              </p>
            ) : null}
          </>
        ) : null}
        <p className="mt-2 text-xs text-ink-muted">
          Granted immediately. One tier and one scope apply to every colleague chosen — the recipients can download
          (and, at higher tiers, comment on or edit) exactly what you share here.
        </p>
        {ledger?.attempt.kind === "grant" ? (
          <BatchReport ledger={ledger} busy={busy} onRetry={retryLedger} onDismiss={() => setLedger(null)} />
        ) : null}
      </section>

      {/* Incoming: requests and grants on MY data. */}
      <section className="panel mb-5 overflow-hidden">
        <div className="border-b border-line-200 p-4">
          <h2 className="font-display font-bold text-lg text-ink">Access to your data</h2>
          <p className="text-sm text-ink-muted">People who requested or hold access to data you uploaded.</p>
        </div>
        {/* The row rules below are `divide-line-200`, not the literal `#efe9e2` that stood there
            until 2026-09-03: an arbitrary hex does not invert, so they stayed a warm light grey on a
            dark card while the panel's own border turned. Non-negotiable 2 — every neutral goes
            through the token ladder. The outgoing list further down carries the identical class for
            the identical reason. */}
        {incoming.length === 0 ? (
          <div className="p-4">
            <EmptyState title="No requests yet" />
          </div>
        ) : (
          <ul className="divide-y divide-line-200">
            {incoming.map((g) => (
              <li key={g.id} className="flex flex-wrap items-center gap-3 p-4">
                <div className="min-w-0 flex-1">
                  <div className="font-medium text-ink">{g.grantee?.name ?? ownerNameById.get(g.granteeId) ?? g.granteeId}</div>
                  <div className="text-xs text-ink-muted">
                    {g.grantee?.email} · {g.allData ? "All data" : `${g.scopeItems?.length ?? 0} records`} {g.requestNote ? `· “${g.requestNote}”` : ""}
                  </div>
                </div>
                <StatusPill status={g.status} />
                <Select className="max-w-44" value={g.tier} onChange={(e) => changeTier(g, e.target.value as DataAccessTier)} disabled={busy || g.status !== "GRANTED"}>
                  <option value="DOWNLOAD">{TIER_LABEL.DOWNLOAD}</option>
                  <option value="COMMENT">{TIER_LABEL.COMMENT}</option>
                  <option value="EDIT">{TIER_LABEL.EDIT}</option>
                </Select>
                <RowActions>
                  {g.status === "PENDING" ? (
                    <>
                      <button className="field-button" disabled={busy} onClick={() => decide(g, "GRANTED")}>
                        Approve
                      </button>
                      <button className={rowAction("danger")} disabled={busy} onClick={() => decide(g, "DENIED")}>
                        Deny
                      </button>
                    </>
                  ) : g.status === "GRANTED" ? (
                    <button className={rowAction("danger")} disabled={busy} onClick={() => revoke(g)}>
                      Revoke
                    </button>
                  ) : (
                    <button className={rowAction("neutral")} disabled={busy} onClick={() => remove(g)}>
                      Remove
                    </button>
                  )}
                </RowActions>
              </li>
            ))}
          </ul>
        )}
      </section>

      {/* Outgoing: access I hold on others' data. */}
      <section className="panel overflow-hidden">
        <div className="border-b border-line-200 p-4">
          <h2 className="font-display font-bold text-lg text-ink">Your access to others&apos; data</h2>
          <p className="text-sm text-ink-muted">Data you requested or were granted access to.</p>
        </div>
        {outgoing.length === 0 ? (
          <div className="p-4">
            <EmptyState title="No access yet" />
          </div>
        ) : (
          <ul className="divide-y divide-line-200">
            {outgoing.map((g) => (
              <li key={g.id} className="flex flex-wrap items-center gap-3 p-4">
                <div className="min-w-0 flex-1">
                  <div className="font-medium text-ink">{g.owner?.name ?? ownerNameById.get(g.ownerId) ?? g.ownerId}</div>
                  <div className="text-xs text-ink-muted">
                    {g.owner?.email} · {TIER_LABEL[g.tier]} · {g.allData ? "All data" : `${g.scopeItems?.length ?? 0} records`}
                  </div>
                </div>
                <StatusPill status={g.status} />
                <RowActions>
                  {g.status === "GRANTED" && tierAtLeast(g.tier, "DOWNLOAD") ? (
                    <button className="field-button" disabled={busy} onClick={() => downloadOwnerData(g.ownerId, g.owner?.name ?? g.ownerId)}>
                      Download data
                    </button>
                  ) : null}
                  <button className={rowAction("neutral")} disabled={busy} onClick={() => remove(g)}>
                    {g.status === "PENDING" ? "Withdraw" : "Remove"}
                  </button>
                </RowActions>
              </li>
            ))}
          </ul>
        )}
      </section>
    </>
  );
}

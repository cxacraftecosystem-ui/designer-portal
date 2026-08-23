"use client";

/**
 * WHO MAY SIGN IN — the platform allow-list, and the queue of people waiting to be let in.
 *
 * THIS IS NOT THE DESIGNER ROSTER (/admin/designers) AND IT IS NOT `User.role`. The designer roster
 * says who the institution recognises as a designer; the role says what somebody may do once they
 * are inside. This list answers the question that comes before both: may this address reach the
 * product at all. The three are kept apart on the server (three tables, three predicates) and they
 * are kept apart here, because the remedies differ — an admin who suspends the wrong one of them
 * takes away something they did not mean to take away, and the person is told the wrong reason.
 *
 * ── THE PENDING SECTION IS THE POINT OF THE SCREEN ───────────────────────────────────────────────
 *
 * A row appears in it when somebody PROVED an identity — a correct password, or a Google token that
 * verified — and was turned away because no ACTIVE row carried their address. That is the
 * notification the requirement asked for, in the only channel either application has: there is no
 * email sender and no push transport in this codebase, so "tell the admins somebody is waiting" is a
 * number on the screens they already open plus this queue. It is rendered FIRST, above the search
 * box and above everything else, because an admin who has to find it has not been notified.
 *
 * ── WHY REJECT IS FINAL, AND WHY THAT IS SAID ON SCREEN ──────────────────────────────────────────
 *
 * A rejected person's next sign-in does NOT put them back in this queue: the server bumps their
 * attempt count, leaves the status alone, and tells them their request was not approved. Any other
 * choice makes the queue unworkable — the admin clears it, the same people retry overnight, and it
 * is full again with entries they already decided. The confirmation says so in words, because an
 * admin who believes Reject is temporary will use it as "not now" and never look at the person
 * again.
 *
 * ── THE DEFECTS THIS SCREEN IS WRITTEN NOT TO REPEAT ─────────────────────────────────────────────
 *
 * `docs/OPEN_FINDINGS.md` closed four defects in the design-workshop viewer picker and three were
 * the same shape: ONE page fetched, filtered in the browser, with nothing on screen saying the
 * answer was a prefix — so eligible people were invisible and looked exactly like people who had
 * never existed. Therefore, on this screen: the search goes to the SERVER (`?search=`), the status
 * filter goes to the SERVER (`?status=`), every list renders the server's `total` and `pages`
 * rather than counting the rows it happens to hold, and there is no `.filter()` over a fetched page
 * anywhere in this file. The fourth defect was an empty truncated answer that told the admin to
 * narrow an already-empty search; the empty states below therefore distinguish "no rows match this
 * search" from "there is genuinely nobody here", and never advise narrowing a search that is empty.
 *
 * ── NOTHING HERE DELETES ─────────────────────────────────────────────────────────────────────────
 *
 * Suspend, never Remove — and the server's DELETE is a suspension that answers 200 with the
 * suspended row. The row holds the joining date, the attempt history and the name of the admin who
 * admitted them; and because the gate reads a MISSING row as PENDING, a real delete would silently
 * put the person back in the queue they were just removed from.
 */

import { useCallback, useEffect, useRef, useState } from "react";
import Link from "next/link";
import { BadgeCheck, Clock, MailPlus, ShieldCheck, UserCheck, UserX } from "lucide-react";

import { EmptyState } from "@/components/EmptyState";
import { Field, TextArea, TextInput } from "@/components/FormControls";
import { PageHeader } from "@/components/PageHeader";
import { Pagination } from "@/components/Pagination";
import { ResizableTh } from "@/components/ResizableTh";
import { RestrictedPanel } from "@/components/settings/RestrictedPanel";
import { RowActions, rowAction } from "@/components/RowActions";
import { SearchInput } from "@/components/SearchInput";
import { useAuth } from "@/components/AuthProvider";
import { useConfirm } from "@/components/dialogs/ConfirmDialog";
import { refreshPendingAccessCount, usePendingAccessCount } from "@/components/hooks/usePendingAccessCount";
import { Dropdown } from "@/components/ui/Dropdown";
import { ApiError } from "@/lib/api";
import {
  addToAccessRoster,
  decideAccessRequest,
  listAccessRoster,
  suspendAccessEntry,
  updateAccessEntry,
  type AccessRosterEntry,
  type AccessRosterPage,
  type AccessStatus
} from "@/lib/accessRoster";
import { formatDate, formatDateTime } from "@/lib/format";
import { assignableRoles, canManageAccessRoster, roleLabel } from "@/lib/permissions";
import type { UserRole } from "@/lib/types";

/** The whole list. Twenty is the designer roster's page size and this table is no denser. */
const PAGE_SIZE = 20;

/**
 * The queue's own page size, deliberately smaller.
 *
 * It sits at the top of a screen whose main list is underneath it, so a queue that paged at twenty
 * would push the roster below two screenfuls of requests on the one day an admin most needs both.
 * Paged rather than capped: a truncated queue is a person nobody ever decides about.
 */
const QUEUE_PAGE_SIZE = 8;

/**
 * Empty means EVERY status, by absence — `buildQuery` drops "" exactly as it drops null, and this
 * app's filters all read that way.
 *
 * THE DEFAULT IS THE WIDEST ONE ON PURPOSE. An admin arrives here holding a message from somebody
 * who cannot sign in, and the row that explains why is a REJECTED or SUSPENDED one — precisely what
 * a tidier default would hide. They would then re-add the address, collect the 409, and still not be
 * able to see what is refusing their colleague.
 */
const STATUS_OPTIONS: { value: "" | AccessStatus; label: string }[] = [
  { value: "", label: "Everyone ever seen" },
  { value: "ACTIVE", label: "Only those who may sign in" },
  { value: "PENDING", label: "Only those waiting for a decision" },
  { value: "REJECTED", label: "Only those refused" },
  { value: "SUSPENDED", label: "Only those suspended" }
];

// Named for the SCREEN and not for the table, because `AccessRosterPage` is the paged-response type
// this file imports and two things with one name in one module is a compile error waiting for the
// next person who adds an import.
export default function PlatformAccessPage() {
  const { user } = useAuth();
  const confirm = useConfirm();
  const permitted = canManageAccessRoster(user);

  /** The badge's number, shared with the nav and the hub tile so the three cannot disagree. */
  const counted = usePendingAccessCount(permitted);

  const [queue, setQueue] = useState<AccessRosterPage | null>(null);
  const [queuePage, setQueuePage] = useState(1);
  const [data, setData] = useState<AccessRosterPage | null>(null);
  const [page, setPage] = useState(1);
  const [query, setQuery] = useState("");
  const [applied, setApplied] = useState("");
  const [status, setStatus] = useState<"" | AccessStatus>("");
  const [editing, setEditing] = useState<AccessRosterEntry | null>(null);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const skipFirstDebounce = useRef(true);
  const formRef = useRef<HTMLFormElement | null>(null);

  /**
   * Fetch generations, not aborts. `listAccessRoster` takes no signal and what matters is IGNORING a
   * late answer: without this, a typed search whose first response lands after the second overwrites
   * the newer rows with older ones and the table shows results for a query nobody can see any more.
   * The designer roster carries the identical guard; see the note there.
   */
  const generation = useRef(0);
  const queueGeneration = useRef(0);

  const loadQueue = useCallback(async () => {
    if (!permitted) return;
    const mine = ++queueGeneration.current;
    try {
      const result = await listAccessRoster({ page: queuePage, pageSize: QUEUE_PAGE_SIZE, status: "PENDING" });
      if (mine !== queueGeneration.current) return;
      setQueue(result);
      // DECIDING THE LAST REQUEST ON A PAGE SHORTENS THE QUEUE UNDERNEATH THE ADMIN. The server
      // answers a page past the end with an empty list and a `total` that is still positive, and
      // this section would then say "Nobody is waiting" over a queue that is not empty — the exact
      // shape of the closed picker defects: rows that exist and cannot be seen. Step back instead.
      if (result.items.length === 0 && result.pages > 0 && queuePage > result.pages) setQueuePage(result.pages);
    } catch (err) {
      if (mine !== queueGeneration.current) return;
      setError(err instanceof Error ? err.message : "Unable to load the approval queue");
    }
  }, [permitted, queuePage]);

  const load = useCallback(async () => {
    if (!permitted) return;
    const mine = ++generation.current;
    try {
      const result = await listAccessRoster({
        page,
        pageSize: PAGE_SIZE,
        search: applied || undefined,
        status: status || undefined
      });
      if (mine !== generation.current) return;
      setData(result);
      setError(null);
      // The same step-back as the queue above, for the same reason: suspending or approving the last
      // row of the last page must not leave the admin on an empty page below a total that says
      // otherwise.
      if (result.items.length === 0 && result.pages > 0 && page > result.pages) setPage(result.pages);
    } catch (err) {
      if (mine !== generation.current) return;
      setError(err instanceof Error ? err.message : "Unable to load the access list");
      // `data` is left standing on a failed refresh, deliberately: replacing a list the admin can
      // still read with "nobody may sign in" is indistinguishable from an empty institution, and on
      // THIS screen that reading is alarming enough to act on.
    }
  }, [applied, page, permitted, status]);

  useEffect(() => {
    load();
  }, [load]);

  useEffect(() => {
    loadQueue();
  }, [loadQueue]);

  // Live search: 350ms after typing stops, Enter applies at once. Both go through one piece of
  // state, so the generation guard above stays the only race protection this screen needs.
  useEffect(() => {
    if (skipFirstDebounce.current) {
      skipFirstDebounce.current = false;
      return;
    }
    const timer = window.setTimeout(() => {
      setApplied(query);
      setPage(1);
    }, 350);
    return () => window.clearTimeout(timer);
  }, [query]);

  /** Re-read everything a decision can have moved, including the badge on the other surfaces. */
  const reloadAll = useCallback(async () => {
    await Promise.all([load(), loadQueue(), refreshPendingAccessCount()]);
  }, [load, loadQueue]);

  async function submit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    // React nulls `currentTarget` across an await, so the FormData is read before any async work —
    // after the first `await` every field arrives empty.
    const formElement = event.currentTarget;
    const form = new FormData(formElement);
    const email = String(form.get("email") ?? "").trim();
    const fullName = String(form.get("fullName") ?? "").trim();
    const notes = String(form.get("notes") ?? "").trim();
    const role = String(form.get("role") ?? "").trim();
    if (!email) return;

    setSaving(true);
    setError(null);
    setNotice(null);
    try {
      if (editing) {
        /*
          A CORRECTION, AND IT CANNOT MOVE THE GATE. The PATCH deliberately refuses `status` on the
          server, so that every transition goes through the decision or suspend endpoint and is
          stamped with who decided and when. The email is not sent either: it is the row's unique key
          and the address the gate compares against, so "fix the spelling of a name" must never be
          able to hand somebody else's access to a different mailbox. Re-typing an address is
          therefore adding an entry, which is the form's other mode.
        */
        const updated = await updateAccessEntry(editing.id, {
          fullName: fullName || null,
          role: (role || null) as UserRole | null,
          notes: notes || null
        });
        setNotice(`Updated the entry for ${updated.email}.`);
        setEditing(null);
        formElement.reset();
        await reloadAll();
        return;
      }
      const created = await addToAccessRoster({
        email,
        fullName: fullName || null,
        role: (role || null) as UserRole | null,
        notes: notes || null
      });
      setNotice(
        `${created.email} may sign in. They join as ${roleLabel(created.admitRole) || "the platform default tier"}, and the account is created the first time they sign in.`
      );
      formElement.reset();
      await reloadAll();
    } catch (err) {
      // A 409 carries the sentence that matters — it names the existing row, says what state it is
      // in, and says that deciding it is a different call — so it is shown verbatim, and the search
      // box is then pointed at the address so the admin is looking at the row rather than reading
      // about it. Straight from the designer roster's fix for the same collision.
      setError(err instanceof Error ? err.message : "Unable to add this address");
      if (err instanceof ApiError && err.status === 409) {
        setQuery(email);
        setStatus("");
        setPage(1);
      }
    } finally {
      setSaving(false);
    }
  }

  async function approve(entry: AccessRosterEntry, role: UserRole | "") {
    const ok = await confirm({
      title: `Let ${entry.email} in?`,
      body: role
        ? `They will be able to sign in immediately, as ${roleLabel(role)}.`
        : "They will be able to sign in immediately, at this platform's default joining tier — the lowest rung — and can be promoted afterwards on Manage users.",
      note:
        "If they already have an account at a lower tier it is raised to match. An account that is already higher is never lowered by approving somebody.",
      confirmLabel: "Approve",
      tone: "warning"
    });
    if (!ok) return;
    try {
      const updated = await decideAccessRequest(entry.id, { decision: "APPROVE", role: role || null });
      setNotice(`${updated.email} may sign in from their next attempt.`);
      setError(null);
      await reloadAll();
    } catch (err) {
      setError(err instanceof Error ? err.message : "Unable to approve this request");
    }
  }

  async function reject(entry: AccessRosterEntry) {
    const ok = await confirm({
      title: `Refuse ${entry.email}?`,
      body: "They will be told their request was reviewed and not approved, and whom to contact.",
      // The tone is danger and the note has to correct what it implies twice over: nothing is
      // deleted, AND the refusal does not expire. An admin using this as "not now" would never see
      // the person again, because a rejected person's next attempt bumps a counter instead of
      // rejoining this queue.
      note:
        "Nothing is deleted — the entry stays on the list, refused, with the record of when they asked and how many times they have tried. Trying again will NOT put them back in this queue: only you can reopen it, by approving them here later.",
      confirmLabel: "Refuse",
      tone: "danger"
    });
    if (!ok) return;
    try {
      const updated = await decideAccessRequest(entry.id, { decision: "REJECT" });
      setNotice(`${updated.email} was refused. The entry stays on the list.`);
      setError(null);
      await reloadAll();
    } catch (err) {
      setError(err instanceof Error ? err.message : "Unable to refuse this request");
    }
  }

  async function suspend(entry: AccessRosterEntry) {
    const ok = await confirm({
      title: `Suspend ${entry.fullName || entry.email}?`,
      body: "They will be refused at their next sign-in, and told that their access to this application was ended.",
      note:
        "The entry is kept — it records when they joined, and that record outlives their access. Approving them again here restores it, and their joining date is not moved by the round trip.",
      confirmLabel: "Suspend",
      tone: "danger"
    });
    if (!ok) return;
    try {
      const updated = await suspendAccessEntry(entry.id);
      setNotice(`${updated.email} can no longer sign in. The entry stays on the list.`);
      setError(null);
      await reloadAll();
    } catch (err) {
      setError(err instanceof Error ? err.message : "Unable to suspend this entry");
    }
  }

  async function restore(entry: AccessRosterEntry) {
    const ok = await confirm({
      title: `Let ${entry.email} back in?`,
      body: "They will be able to sign in again immediately.",
      note: entry.joinedAt
        ? `Their joining date stays ${formatDate(entry.joinedAt)} — somebody who joined then, lost access and was let back in has still been here since then.`
        : undefined,
      confirmLabel: "Restore",
      tone: "warning"
    });
    if (!ok) return;
    try {
      const updated = await decideAccessRequest(entry.id, { decision: "APPROVE" });
      setNotice(`${updated.email} can sign in again.`);
      setError(null);
      await reloadAll();
    } catch (err) {
      setError(err instanceof Error ? err.message : "Unable to restore this entry");
    }
  }

  function startEdit(entry: AccessRosterEntry) {
    setEditing(entry);
    setError(null);
    setNotice(null);
    // The form is above the table; on a long list the edit that was just asked for would otherwise
    // happen off screen. Deferred a frame because the form remounts on its `key` and only then has
    // its final height.
    requestAnimationFrame(() => formRef.current?.scrollIntoView({ block: "start", behavior: "smooth" }));
  }

  const header = (
    <PageHeader
      title="Who may sign in"
      description="Every address allowed into this application, plus the people waiting for a decision. Nothing here is ever deleted — access is granted, refused, suspended and restored, and the entry keeps the history either way."
      icon={<ShieldCheck className="h-5 w-5" aria-hidden />}
    />
  );

  /**
   * Gated three times over — ROUTE_GUARDS above this page, `require_access_manager` on every request
   * it would make, and here. The panel is still rendered, because a client guard that only hides a
   * nav item is not a guard, and somebody on a stale bookmark deserves a sentence rather than an
   * empty screen.
   */
  if (!permitted) {
    return (
      <>
        {header}
        <RestrictedPanel
          title="Admin access required"
          body={
            `Deciding who may sign in — and reading the queue of people waiting, which is a list of named individuals who tried to get in — is admin work: ` +
            // `roleLabel` answers "" for an absent user; AppShell never renders a protected page
            // without one, but a sentence reading "  does not open it" is a worse way to discover
            // that than a fallback nobody will see.
            `${roleLabel(user?.role) || "your tier"} does not open it, and the API refuses the same request for the same reason. ` +
            `An admin or the master admin can approve, refuse, suspend and restore people here.`
          }
        />
      </>
    );
  }

  const grantable = assignableRoles(user);
  const queueRows = queue?.items ?? [];
  const rows = data?.items ?? [];

  return (
    <>
      {header}

      {/*
        THE ONE SENTENCE THAT STOPS AN ADMIN WORKING THE WRONG LIST. Two screens in this hub decide
        who can sign in and they read alike; the difference is that this one decides whether somebody
        may reach the application at all, and the other decides whether they are empanelled as a
        designer. An admin who suspends the wrong one takes away something they did not mean to, and
        the person is then told the wrong reason on the sign-in screen.
      */}
      <p className="mb-4 text-sm leading-6 text-ink-500">
        This is not the{" "}
        <Link href="/admin/designers" className="font-medium text-purple-700 hover:underline">
          designer roster
        </Link>
        , which is the narrower question of who the institution recognises as a designer. This list decides who may reach
        the application at all — and the master admin is never gated by it, which is what guarantees somebody can always
        get in here and let people back in.
      </p>

      {error ? (
        <div role="alert" className="mb-4 rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">
          {error}
        </div>
      ) : null}
      {notice ? (
        <div className="mb-4 rounded-md border border-line-200 bg-surface-50 px-3 py-2 text-sm text-ink-700">{notice}</div>
      ) : null}

      {/*
        THE QUEUE, FIRST AND ALWAYS RENDERED — including when it is empty. A section that vanished
        when nobody was waiting would leave an admin unable to tell "nobody has asked" from "the
        queue is somewhere else on this page", and the empty state is the only place the mechanism
        is ever explained to them.
      */}
      <section className="panel mb-6 overflow-hidden">
        <div className="flex flex-wrap items-center gap-3 border-b border-line-200 bg-surface-50 px-4 py-3">
          <div className="grid h-9 w-9 place-items-center rounded-md bg-amber-500/15 text-amber-700">
            <Clock className="h-5 w-5" aria-hidden />
          </div>
          <div className="min-w-0 flex-1">
            <h2 className="font-display font-bold text-ink-900">
              Waiting for a decision
              {queue ? (
                <span className="ml-2 rounded-full bg-amber-100 px-2 py-0.5 align-middle text-xs font-semibold text-amber-800">
                  {/* The SERVER's total, never `queueRows.length` — the queue is paged, and a badge
                      that counted the page would under-report the moment a ninth person asked. */}
                  {queue.total}
                </span>
              ) : null}
            </h2>
            <p className="text-sm text-ink-500">
              Each of these people proved who they are — a correct password, or a verified Google account — and was turned
              away because this list did not carry their address. They are told they are waiting for you.
            </p>
          </div>
        </div>

        {/*
          THE CEILING, SAID OUT LOUD. Past `capacity` the server stops recording new requests and
          tells the person to contact an administrator directly. An admin who is not told this reads
          a queue that has stopped growing as "nobody is asking" — the one reading that is certainly
          wrong — and every person turned away in the meantime is invisible to this product forever.
        */}
        {counted?.capReached ? (
          <div className="border-b border-amber-500/30 bg-amber-100 px-4 py-2 text-xs leading-5 text-amber-900">
            This queue is at its ceiling of {counted.capacity} waiting requests, so new ones are NO LONGER BEING RECORDED —
            anybody turned away now is told that requests are temporarily closed and to contact an administrator directly.
            Decide the requests below to make room.
          </div>
        ) : null}

        {queue === null ? (
          // null is "still loading"; [] is "genuinely nobody". Saying "nobody is waiting" during a
          // fetch is wrong in the direction that makes an admin close the page.
          <div className="p-4 text-sm text-ink-700">Loading the approval queue…</div>
        ) : queueRows.length === 0 ? (
          <div className="p-4">
            <EmptyState
              title="Nobody is waiting"
              body="When somebody who is not on this list proves their identity at the sign-in screen, they appear here — with their address, when they asked and how many times they have tried — and are told an administrator has to approve them."
            />
          </div>
        ) : (
          <ul className="divide-y divide-line-200">
            {queueRows.map((entry) => (
              <QueueRow
                key={entry.id}
                entry={entry}
                grantable={grantable}
                onApprove={(role) => approve(entry, role)}
                onReject={() => reject(entry)}
              />
            ))}
          </ul>
        )}
        {queue && queue.total > QUEUE_PAGE_SIZE ? (
          <Pagination page={queue.page} pages={queue.pages} total={queue.total} onPage={setQueuePage} />
        ) : null}
      </section>

      <form
        ref={formRef}
        onSubmit={submit}
        // Remounted when the row being edited changes, so every `defaultValue` is re-seeded — an
        // uncontrolled form otherwise keeps the previous row's text in its boxes.
        key={editing?.id ?? "new"}
        className="panel mb-5 grid gap-4 p-4"
      >
        <div>
          <h2 className="font-display text-lg font-bold text-ink-900">
            {editing ? `Correct the entry for ${editing.email}` : "Let somebody in by address"}
          </h2>
          <p className="mt-1 text-sm leading-6 text-ink-muted">
            {editing
              ? "The name, tier and note are your own record of whom you admitted and why. Changing them here cannot change whether this person may sign in — use Approve, Refuse, Suspend or Restore for that."
              : "No account has to exist yet: the address is admitted now and the account is created the first time that person signs in. Adding somebody here IS approving them, so they never appear in the queue above."}
          </p>
        </div>
        <div className="grid gap-3 md:grid-cols-2 lg:grid-cols-3">
          <Field label="Email address" required>
            <TextInput
              name="email"
              type="email"
              required
              defaultValue={editing?.email ?? ""}
              placeholder="colleague@institution.ac.in"
              // READ-ONLY WHILE CORRECTING AN ENTRY, and this is a rule rather than a nicety: the
              // address IS the gate, so an admin who edits it while meaning to fix a name would hand
              // one person's admission to another mailbox — and the person who lost it would simply
              // stop being able to sign in, with the entry on screen still saying they may.
              readOnly={editing !== null}
              // Lower-cased on the server before it is stored and before it is compared with the
              // address signing in, so capitals typed here are harmless.
              autoComplete="off"
            />
          </Field>
          <Field label="Full name">
            <TextInput name="fullName" maxLength={180} defaultValue={editing?.fullName ?? ""} />
          </Field>
          <Field label="Joins as">
            {/*
              A native select rather than the app's Dropdown: this one sits inside an uncontrolled
              form and is read out of FormData with everything else, and a controlled component here
              would need state whose only job is to be read back at submit.

              The empty option is FIRST and is the platform's documented default — the lowest rung.
              A new joiner is promoted deliberately, on Manage users, rather than by whoever happened
              to type their address in.
            */}
            <select
              name="role"
              defaultValue={editing?.admitRole ?? ""}
              className="field-input"
              aria-label="The tier this person joins at"
            >
              <option value="">Default joining tier (lowest rung)</option>
              {grantable.map((role) => (
                <option key={role} value={role}>
                  {roleLabel(role)}
                </option>
              ))}
            </select>
          </Field>
        </div>
        <Field label="Note">
          <TextArea
            name="notes"
            maxLength={4000}
            rows={2}
            defaultValue={editing?.notes ?? ""}
            placeholder="Who they are and why they were admitted."
          />
        </Field>
        <div className="flex flex-wrap gap-2">
          <button className="field-button" disabled={saving}>
            <MailPlus className="h-4 w-4" aria-hidden />
            {saving ? "Saving…" : editing ? "Update entry" : "Add to the list"}
          </button>
          {editing ? (
            <button type="button" className="field-button-secondary" onClick={() => setEditing(null)}>
              Cancel
            </button>
          ) : null}
        </div>
      </form>

      <div className="mb-4 grid gap-2 sm:grid-cols-[1fr_18rem]">
        <SearchInput
          value={query}
          onChange={setQuery}
          onSubmit={() => {
            setApplied(query);
            setPage(1);
          }}
          placeholder="Search by email, name or note"
        />
        <Dropdown
          value={status}
          onChange={(next) => {
            setStatus(next as "" | AccessStatus);
            setPage(1);
          }}
          options={STATUS_OPTIONS}
          ariaLabel="Filter by standing"
          // A dropdown that filters the screen it sits on must not advance focus on select: jumping
          // away from the control being adjusted is wrong when the control IS the adjustment.
          advanceOnSelect={false}
        />
      </div>

      <section className="panel overflow-hidden">
        {data === null ? (
          <div className="p-4 text-sm text-ink-700">Loading the list…</div>
        ) : rows.length === 0 ? (
          <div className="p-4">
            <EmptyState
              title="Nobody matches"
              body={
                // NEVER "narrow your search" over an empty search. That advice, printed over an
                // empty result an admin had not searched for, is the closed defect from the viewer
                // picker arriving on a new screen.
                applied || status
                  ? "No entry matches this search or filter. Clear both to see everyone this application has ever admitted, refused or suspended."
                  : "Nobody has been admitted or turned away yet. Add the first address above — the master admin can always sign in regardless of this list, which is what makes it safe to start empty."
              }
            />
          </div>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full min-w-[1000px] text-left text-sm">
              <thead className="bg-surface-50 text-xs uppercase text-ink-500">
                <tr>
                  <ResizableTh>Person</ResizableTh>
                  <ResizableTh>Standing</ResizableTh>
                  <ResizableTh>Joined</ResizableTh>
                  <ResizableTh>Requests</ResizableTh>
                  <ResizableTh>Note</ResizableTh>
                  <ResizableTh className="text-right">Actions</ResizableTh>
                </tr>
              </thead>
              <tbody className="divide-y divide-line-200">
                {rows.map((entry) => (
                  <tr key={entry.id}>
                    <td className="px-4 py-3">
                      <div className="font-medium text-ink-900">
                        {/* The name is whatever an ADMIN typed. A person who has only ever been
                            refused has none, and that absence is stated rather than left blank —
                            nothing here is ever populated from an unverified profile. */}
                        {entry.fullName || <span className="text-ink-500">Name not recorded</span>}
                      </div>
                      <div className="text-xs text-ink-500">{entry.email}</div>
                    </td>
                    <td className="px-4 py-3">
                      <StandingChip entry={entry} />
                    </td>
                    <td className="px-4 py-3 text-ink-700">
                      {/* The requirement's "date of joining the platform". Written once and never
                          moved by a suspension and restore. */}
                      {entry.joinedAt ? formatDate(entry.joinedAt) : "—"}
                      <span className="block text-xs text-ink-500">
                        {entry.firstSeenAt
                          ? `First signed in ${formatDate(entry.firstSeenAt)}`
                          : entry.joinedAt
                            ? "Has not signed in yet"
                            : "Has never been admitted"}
                      </span>
                    </td>
                    <td className="px-4 py-3 text-ink-700">
                      {entry.attemptCount > 0 ? (
                        <>
                          {entry.attemptCount} refused attempt{entry.attemptCount === 1 ? "" : "s"}
                          <span className="block text-xs text-ink-500">
                            {entry.lastAttemptAt ? `Last ${formatDateTime(entry.lastAttemptAt)}` : "Date not recorded"}
                          </span>
                        </>
                      ) : (
                        "—"
                      )}
                    </td>
                    <td className="max-w-xs px-4 py-3 text-ink-700">
                      {entry.notes ? <span className="line-clamp-3 whitespace-pre-line">{entry.notes}</span> : "—"}
                    </td>
                    <td className="px-4 py-3 text-right">
                      <RowActions>
                        <button type="button" className={rowAction("edit")} onClick={() => startEdit(entry)}>
                          Edit
                        </button>
                        {entry.status === "ACTIVE" ? (
                          // Never labelled "Delete". The verb has to say what happens, and what
                          // happens is that the entry stays.
                          <button type="button" className={rowAction("danger")} onClick={() => suspend(entry)}>
                            Suspend
                          </button>
                        ) : (
                          <button type="button" className={rowAction("positive")} onClick={() => restore(entry)}>
                            <UserCheck className="h-3.5 w-3.5" aria-hidden />
                            {entry.status === "PENDING" ? "Approve" : "Let back in"}
                          </button>
                        )}
                        {entry.status === "PENDING" ? (
                          <button type="button" className={rowAction("danger")} onClick={() => reject(entry)}>
                            Refuse
                          </button>
                        ) : null}
                      </RowActions>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
        {data ? <Pagination page={data.page} pages={data.pages} total={data.total} onPage={setPage} /> : null}
      </section>
    </>
  );
}

/**
 * One waiting request: who, when they asked, how hard they have been trying, and the two answers.
 *
 * THE TIER IS CHOSEN AT THE MOMENT OF APPROVAL and defaults to the platform's lowest rung, because
 * that is this platform's documented rule for a new joiner — "all the users by default join as the
 * lowest rung unless promoted there itself". The select offers only tiers the deciding admin could
 * grant on Manage users; the server enforces the same ceiling, so this is a mirror rather than the
 * rule.
 */
function QueueRow({
  entry,
  grantable,
  onApprove,
  onReject
}: {
  entry: AccessRosterEntry;
  grantable: UserRole[];
  onApprove: (role: UserRole | "") => void;
  onReject: () => void;
}) {
  const [role, setRole] = useState<UserRole | "">("");

  return (
    <li className="flex flex-wrap items-start gap-3 px-4 py-3">
      <div className="min-w-0 flex-1">
        <div className="truncate font-medium text-ink-900">{entry.email}</div>
        <div className="mt-0.5 text-xs text-ink-500">
          {/* WHEN they asked and HOW MANY TIMES, both, because the pair is the whole signal an admin
              has. One attempt three weeks ago is somebody who gave up; eleven attempts today is
              somebody standing in a courtyard unable to work. */}
          {entry.requestedAt ? `Asked ${formatDateTime(entry.requestedAt)}` : "Asked at an unrecorded time"}
          {" · "}
          {entry.attemptCount} attempt{entry.attemptCount === 1 ? "" : "s"}
          {entry.lastAttemptAt ? `, last ${formatDateTime(entry.lastAttemptAt)}` : ""}
        </div>
      </div>
      <div className="flex flex-wrap items-center gap-2">
        {/* Native, and no filter box, for the same reasons as the invite form's picker above: the
            grantable tiers are the role ladder — at most seven rows, a fixed vocabulary — and this
            control is rendered once for every entry in the queue, which is where a portalled panel
            per row stops being free. `aria-label` carries the name because there is no visible label
            beside it. */}
        <select
          value={role}
          onChange={(event) => setRole(event.target.value as UserRole | "")}
          className="field-input h-9 py-0 text-sm"
          aria-label={`The tier ${entry.email} joins at`}
        >
          <option value="">Joins at the default tier</option>
          {grantable.map((option) => (
            <option key={option} value={option}>
              Joins as {roleLabel(option)}
            </option>
          ))}
        </select>
        <button type="button" className={rowAction("positive")} onClick={() => onApprove(role)}>
          <UserCheck className="h-3.5 w-3.5" aria-hidden />
          Approve
        </button>
        <button type="button" className={rowAction("danger")} onClick={onReject}>
          <UserX className="h-3.5 w-3.5" aria-hidden />
          Refuse
        </button>
      </div>
    </li>
  );
}

/**
 * What this entry means for its holder, as a chip.
 *
 * COLOUR NEVER CARRIES THE MEANING ON ITS OWN — every state is worded — and the two that end in a
 * refusal carry their date, because "when did this person lose access" is the question the row is
 * usually opened to answer. `admitRole` rides along on the admitted ones so an admin can see at a
 * glance that somebody was let in at a tier they did not intend.
 */
function StandingChip({ entry }: { entry: AccessRosterEntry }) {
  if (entry.status === "ACTIVE") {
    return (
      <span className="inline-flex flex-col gap-0.5">
        <span className="inline-flex w-fit items-center gap-1.5 rounded-full border border-success-600/25 bg-success-100 px-2.5 py-1 text-xs font-medium text-success-600">
          <BadgeCheck className="h-3.5 w-3.5" aria-hidden />
          May sign in
        </span>
        <span className="text-xs text-ink-500">
          {entry.admitRole ? `as ${roleLabel(entry.admitRole)}` : "at the default joining tier"}
        </span>
      </span>
    );
  }
  if (entry.status === "PENDING") {
    return (
      <span className="inline-flex w-fit items-center gap-1.5 rounded-full border border-amber-500/30 bg-amber-100 px-2.5 py-1 text-xs font-medium text-amber-800">
        <Clock className="h-3.5 w-3.5" aria-hidden />
        Waiting for a decision
      </span>
    );
  }
  return (
    <span className="inline-flex flex-col gap-0.5">
      <span className="inline-flex w-fit items-center gap-1.5 rounded-full border border-error-600/25 bg-error-100 px-2.5 py-1 text-xs font-medium text-error-600">
        {entry.status === "REJECTED" ? "Refused" : "Suspended"}
      </span>
      <span className="text-xs text-ink-500">
        {entry.decidedAt ? `since ${formatDate(entry.decidedAt)}` : "date not recorded"}
      </span>
    </span>
  );
}

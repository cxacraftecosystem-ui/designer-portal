"use client";

/**
 * The designer roster — the list that decides who may sign in at all.
 *
 * THIS IS NOT A LIST OF ACCOUNTS AND IT IS NOT `User.role`. A roster row is the institution's
 * statement that an individual is empanelled; the role column says what somebody may DO once they
 * are in. The two are kept apart on purpose, and the reason is on the backend service: revoking
 * access by DEMOTING an account is retroactive, because the role is read when deciding who may
 * review whose work, so a demotion silently rewrites the standing of every workshop that designer
 * ever ran. Suspending a roster row ends their sessions and leaves two years of authorship exactly
 * as it was.
 *
 * A ROW USUALLY EXISTS BEFORE THE ACCOUNT DOES, which is the point of empanelling by email: the
 * admin adds the address, and the account provisions itself the first time that person signs in
 * through Google, at which moment `firstSeenAt` is stamped. That column is the one thing this
 * screen exists to show — an invitation that never arrived looks exactly like one that was ignored,
 * and without it an admin has no way in April to tell which of five designers added in March ever
 * opened the app.
 *
 * THERE IS NO DELETE, ANYWHERE ON THIS PAGE, and there must never be one. The roster is the RECORD
 * that somebody was recognised, and that record outlives their access: an audit two years from now
 * asks who was empanelled under which programme, and a deleted row answers "nobody". `DELETE` on
 * the API is a suspension that answers 200 with the suspended row, so the row stays on screen —
 * struck through, dated, and one click from being restored.
 *
 * SUSPENDED ROWS ARE LISTED BY DEFAULT. An admin arrives here because a designer says they cannot
 * log in, and the row refusing them is the one they need to SEE. Hiding it leaves them re-adding an
 * address the unique index then rejects, with the explanation nowhere on screen.
 */

import { useCallback, useEffect, useRef, useState } from "react";
import Link from "next/link";
import { BadgeCheck, MailPlus, RotateCcw } from "lucide-react";

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
import { Dropdown } from "@/components/ui/Dropdown";
import { ApiError } from "@/lib/api";
import {
  addDesignerToRoster,
  listDesignerDirectory,
  listDesignerRoster,
  restoreDesignerRosterEntry,
  rosterInvitationLabel,
  rosterUpdateBody,
  suspendDesignerRosterEntry,
  updateDesignerRosterEntry,
  type DesignerRosterEntry
} from "@/lib/designers";
import { formatDate, formatDateTime } from "@/lib/format";
import { canManageDesignerRoster, roleLabel } from "@/lib/permissions";
import type { PageResult } from "@/lib/types";

const PAGE_SIZE = 20;

/**
 * Empty means EVERYONE, by absence — the same rule every other filter in this app follows, and the
 * reason `buildQuery` drops "" exactly as it drops null. The default is deliberately the wider of
 * the two; see the file header.
 */
const STANDING_OPTIONS = [
  { value: "", label: "Everyone ever empanelled" },
  { value: "active", label: "Only those who may sign in" }
];

/** How many accounts `GET /designers/directory` will return before it stops. Server-side `take`. */
const DIRECTORY_CAP = 500;

export default function DesignerRosterPage() {
  const { user } = useAuth();
  const confirm = useConfirm();
  const permitted = canManageDesignerRoster(user);

  const [data, setData] = useState<PageResult<DesignerRosterEntry> | null>(null);
  const [page, setPage] = useState(1);
  const [query, setQuery] = useState("");
  const [applied, setApplied] = useState("");
  const [standing, setStanding] = useState("");
  const [editing, setEditing] = useState<DesignerRosterEntry | null>(null);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  /** Lower-cased email -> the id of the account that signed up under it, for "Open profile". */
  const [accounts, setAccounts] = useState<Map<string, string>>(new Map());
  const [directoryCapped, setDirectoryCapped] = useState(false);
  const skipFirstDebounce = useRef(true);
  const formRef = useRef<HTMLFormElement | null>(null);

  /**
   * List pages count fetch generations rather than aborting: `listDesignerRoster` takes no signal,
   * and what matters is IGNORING the late answer. Without this a typed search whose first response
   * arrives after the second overwrites the newer list with older rows, and the screen shows results
   * for a query nobody can see any more.
   */
  const generation = useRef(0);

  const load = useCallback(async () => {
    if (!permitted) return;
    const mine = ++generation.current;
    try {
      const result = await listDesignerRoster({
        page,
        pageSize: PAGE_SIZE,
        search: applied || undefined,
        activeOnly: standing === "active"
      });
      if (mine !== generation.current) return;
      setData(result);
      setError(null);
    } catch (err) {
      if (mine !== generation.current) return;
      setError(err instanceof Error ? err.message : "Unable to load the designer roster");
      // `data` is deliberately left standing on a failed refresh: replacing a roster the admin can
      // still read with "nobody is empanelled" is indistinguishable from an empty institution.
    }
  }, [applied, page, permitted, standing]);

  useEffect(() => {
    load();
  }, [load]);

  /**
   * Which roster emails have an account behind them, so a row can offer that person's profile.
   *
   * The roster is keyed by email and carries no user id — it usually predates the account — so this
   * is the only way to get from a row to a profile. Best effort: a failure leaves the action off the
   * rows rather than emptying the page, and the cap is reported rather than hidden, because a
   * missing link on a row whose account exists is exactly the kind of silence that reads as "this
   * designer never signed up".
   */
  useEffect(() => {
    if (!permitted) return;
    let cancelled = false;
    listDesignerDirectory({ includeSuspended: true })
      .then((rows) => {
        if (cancelled) return;
        setAccounts(new Map(rows.map((row) => [row.email.toLowerCase(), row.id])));
        setDirectoryCapped(rows.length >= DIRECTORY_CAP);
      })
      .catch(() => {
        /* no profile links this session; the roster itself is unaffected */
      });
    return () => {
      cancelled = true;
    };
  }, [permitted]);

  // Live search: 350ms after typing stops, Enter applies immediately. Both go through the same
  // state so the generation guard above stays the only race protection needed.
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

  async function submit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    // React nulls `event.currentTarget` across an await, so the FormData is read before any async
    // work — after the first `await` it is null and every field arrives empty.
    const formElement = event.currentTarget;
    const form = new FormData(formElement);
    const email = String(form.get("email") ?? "").trim();
    const fullName = String(form.get("fullName") ?? "");
    const institution = String(form.get("institution") ?? "");
    const notes = String(form.get("notes") ?? "");
    if (!email) return;

    setSaving(true);
    setError(null);
    setNotice(null);
    try {
      if (editing) {
        const updated = await updateDesignerRosterEntry(
          editing.id,
          // `email` travels only when it is non-blank and the three descriptive columns carry an
          // explicit null when emptied — see `rosterUpdateBody`. `isActive` is deliberately absent:
          // correcting a spelling must never be able to change whether somebody can sign in.
          rosterUpdateBody({ email, fullName, institution, notes })
        );
        setNotice(`Updated the roster entry for ${updated.email}.`);
        setEditing(null);
      } else {
        const created = await addDesignerToRoster({
          email,
          fullName: fullName.trim() || null,
          institution: institution.trim() || null,
          notes: notes.trim() || null
        });
        setNotice(
          `${created.email} is on the roster. They can sign in with Google using that address, and the account will be created as a Designer the first time they do.`
        );
      }
      formElement.reset();
      await load();
    } catch (err) {
      // A 409 carries the sentence that matters — it names the existing row, says whether it is
      // suspended, and says that restoring is a PATCH rather than a second add — so it is shown
      // verbatim. The search box is then pointed at the address, because the row it is talking
      // about is on this very page and the admin should be looking at it rather than reading about
      // it. That is the whole fix for "an admin re-adding an email the unique index rejects, with
      // no explanation visible anywhere in the UI".
      setError(err instanceof Error ? err.message : "Unable to save this roster entry");
      if (err instanceof ApiError && err.status === 409) {
        setQuery(email);
        setStanding("");
        setPage(1);
      }
    } finally {
      setSaving(false);
    }
  }

  async function suspend(entry: DesignerRosterEntry) {
    const ok = await confirm({
      title: `Suspend ${entry.fullName || entry.email}?`,
      body: "They will be refused at their next request and at every sign-in after it, until the entry is restored.",
      // The tone is danger because access is being taken away, but the note has to correct the word
      // the tone implies: nothing is deleted here, and an admin who believes otherwise will hesitate
      // over something that is one click from being undone.
      note:
        "The roster entry is kept — this records that they were empanelled, and that record outlives their access. Nothing they have recorded is touched, and Restore gives the access back.",
      confirmLabel: "Suspend",
      tone: "danger"
    });
    if (!ok) return;
    try {
      const updated = await suspendDesignerRosterEntry(entry.id);
      setNotice(
        `${updated.email} is suspended${updated.revokedAt ? ` as of ${formatDate(updated.revokedAt)}` : ""}. The entry stays on the roster.`
      );
      setError(null);
      await load();
    } catch (err) {
      setError(err instanceof Error ? err.message : "Unable to suspend this roster entry");
    }
  }

  async function restore(entry: DesignerRosterEntry) {
    const ok = await confirm({
      title: `Restore ${entry.fullName || entry.email}?`,
      body: "They will be able to sign in again immediately.",
      note: entry.revokedAt
        ? `They were suspended on ${formatDate(entry.revokedAt)}. Restoring clears that date, because a row cannot be both active and revoked.`
        : undefined,
      confirmLabel: "Restore",
      tone: "warning"
    });
    if (!ok) return;
    try {
      const updated = await restoreDesignerRosterEntry(entry.id);
      setNotice(`${updated.email} can sign in again.`);
      setError(null);
      await load();
    } catch (err) {
      setError(err instanceof Error ? err.message : "Unable to restore this roster entry");
    }
  }

  function startEdit(entry: DesignerRosterEntry) {
    setEditing(entry);
    setError(null);
    setNotice(null);
    // The form is above the table and a long roster puts it off screen, so the edit that was just
    // asked for would happen somewhere the admin cannot see. Deferred one frame because the form
    // remounts on its `key` and only then has its final height and position.
    requestAnimationFrame(() => formRef.current?.scrollIntoView({ block: "start", behavior: "smooth" }));
  }

  const header = (
    <PageHeader
      title="Designer roster"
      description="Who the institution recognises as a designer. An address on this list may sign in; one that is suspended may not, and the entry stays either way."
      icon={<BadgeCheck className="h-5 w-5" aria-hidden />}
    />
  );

  /**
   * The route is gated twice over — `ROUTE_GUARDS` refuses it above this page and
   * `require_designer_roster_manager` refuses every request it would make — but the panel is still
   * rendered here, because a client-side guard that only hides a nav item is not a guard, and
   * somebody arriving on a stale bookmark deserves a sentence rather than an empty screen.
   */
  if (!permitted) {
    return (
      <>
        {header}
        <RestrictedPanel
          title="Admin access required"
          body={
            `The designer roster is a list of named individuals and their institutional standing, so reading it is admin work as much as ` +
            // `roleLabel` answers "" for an absent user. AppShell never renders a protected page
            // without one, but a sentence that reads "  does not open it" is a worse way to find
            // that out than a fallback nobody will ever see.
            `writing it is: ${roleLabel(user?.role) || "your tier"} does not open it, and the API refuses the same request for the same reason. ` +
            `An admin or the master admin can add, suspend and restore designers here.`
          }
        />
      </>
    );
  }

  const rows = data?.items ?? [];

  return (
    <>
      {header}

      {error ? (
        <div className="mb-4 rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">{error}</div>
      ) : null}
      {notice ? (
        <div className="mb-4 rounded-md border border-line-200 bg-surface-50 px-3 py-2 text-sm text-ink-700">{notice}</div>
      ) : null}

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
            {editing ? `Correct the entry for ${editing.email}` : "Add a designer to the roster"}
          </h2>
          <p className="mt-1 text-sm leading-6 text-ink-muted">
            {editing
              ? "The name, institution and note are the admin’s own record of whom they empanelled and why. Changing them cannot change whether this person may sign in — use Suspend or Restore for that."
              : "The email address is the only thing needed, and no account has to exist yet: that is how somebody is empanelled before they have ever opened the app. The first time they sign in with Google under this address, the account is created and promoted to Designer."}
          </p>
        </div>
        <div className="grid gap-3 md:grid-cols-2 lg:grid-cols-3">
          <Field label="Email address" required>
            <TextInput
              name="email"
              type="email"
              required
              defaultValue={editing?.email ?? ""}
              placeholder="designer@institution.ac.in"
              // Lower-cased on the server before it is stored and before it is compared to the
              // address signing in, so capitals here are harmless — see `normalise_email`.
              autoComplete="off"
            />
          </Field>
          <Field label="Full name">
            <TextInput name="fullName" maxLength={180} defaultValue={editing?.fullName ?? ""} />
          </Field>
          <Field label="Institution">
            <TextInput name="institution" maxLength={180} defaultValue={editing?.institution ?? ""} />
          </Field>
        </div>
        <Field label="Note">
          <TextArea
            name="notes"
            maxLength={4000}
            rows={2}
            defaultValue={editing?.notes ?? ""}
            placeholder="Which programme they were empanelled under, and when."
          />
        </Field>
        <div className="flex flex-wrap gap-2">
          <button className="field-button" disabled={saving}>
            <MailPlus className="h-4 w-4" aria-hidden />
            {saving ? "Saving…" : editing ? "Update entry" : "Add to roster"}
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
          placeholder="Search by email, name or institution"
        />
        <Dropdown
          value={standing}
          onChange={(next) => {
            setStanding(next);
            setPage(1);
          }}
          options={STANDING_OPTIONS}
          ariaLabel="Filter by standing"
          // A dropdown that filters the screen it sits on must not advance focus on select: jumping
          // away from the control being adjusted is wrong when the control IS the adjustment.
          advanceOnSelect={false}
        />
      </div>

      {directoryCapped ? (
        <p className="mb-4 rounded-md border border-amber-500/30 bg-amber-100 px-3 py-2 text-xs leading-5 text-amber-800">
          The account list this page reads to match a roster entry to a person stops at {DIRECTORY_CAP} accounts, so “Open
          profile” may be missing from a row whose account does exist. The roster itself is complete and paged below.
        </p>
      ) : null}

      <section className="panel overflow-hidden">
        {data === null ? (
          // null is "still loading" and [] is "genuinely none" — saying "nobody is empanelled"
          // during a fetch is both wrong and alarming on this particular screen.
          <div className="p-4 text-sm text-ink-700">Loading the roster…</div>
        ) : rows.length === 0 ? (
          <div className="p-4">
            <EmptyState
              title="No roster entries found"
              body={
                applied || standing
                  ? "No entry matches this search. Clear it to see everyone who has ever been empanelled, suspended entries included."
                  : "Nobody has been empanelled yet. Add the first designer above — the address is all that is needed, and their account will create itself when they sign in."
              }
            />
          </div>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full min-w-[1000px] text-left text-sm">
              <thead className="bg-surface-50 text-xs uppercase text-ink-500">
                <tr>
                  <ResizableTh>Designer</ResizableTh>
                  <ResizableTh>Institution</ResizableTh>
                  <ResizableTh>May sign in</ResizableTh>
                  <ResizableTh>Invitation</ResizableTh>
                  <ResizableTh>Note</ResizableTh>
                  <ResizableTh className="text-right">Actions</ResizableTh>
                </tr>
              </thead>
              <tbody className="divide-y divide-line-200">
                {rows.map((entry) => {
                  const accountId = accounts.get(entry.email.toLowerCase());
                  return (
                    <tr key={entry.id}>
                      <td className="px-4 py-3">
                        <div className="font-medium text-ink-900">
                          {/* The name is whatever the admin typed — there may be no account to read
                              one from — so its absence is stated rather than left as an empty cell. */}
                          {entry.fullName || <span className="text-ink-500">Name not recorded</span>}
                        </div>
                        <div className="text-xs text-ink-500">{entry.email}</div>
                      </td>
                      <td className="px-4 py-3 text-ink-700">{entry.institution ?? "—"}</td>
                      <td className="px-4 py-3">
                        <StandingChip entry={entry} />
                      </td>
                      <td className="px-4 py-3 text-ink-700">
                        {/* Never "never": a null firstSeenAt is a statement about an invitation, not
                            about a person, and the two read very differently to an admin chasing it. */}
                        {rosterInvitationLabel(entry)}
                        <span className="block text-xs text-ink-500">
                          {entry.firstSeenAt
                            ? formatDateTime(entry.firstSeenAt)
                            : `Added ${entry.createdAt ? formatDate(entry.createdAt) : "—"}`}
                        </span>
                      </td>
                      <td className="max-w-xs px-4 py-3 text-ink-700">
                        {entry.notes ? <span className="line-clamp-3 whitespace-pre-line">{entry.notes}</span> : "—"}
                      </td>
                      <td className="px-4 py-3 text-right">
                        <RowActions>
                          <button type="button" className={rowAction("edit")} onClick={() => startEdit(entry)}>
                            Edit
                          </button>
                          {accountId ? (
                            <Link className={rowAction("neutral")} href={`/designers/${accountId}/profile`}>
                              Open profile
                            </Link>
                          ) : null}
                          {entry.isActive ? (
                            // Never labelled "Delete". The verb has to say what happens, and what
                            // happens is that the row stays.
                            <button type="button" className={rowAction("danger")} onClick={() => suspend(entry)}>
                              Suspend
                            </button>
                          ) : (
                            <button type="button" className={rowAction("positive")} onClick={() => restore(entry)}>
                              <RotateCcw className="h-3.5 w-3.5" aria-hidden />
                              Restore
                            </button>
                          )}
                        </RowActions>
                      </td>
                    </tr>
                  );
                })}
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
 * Whether this entry admits its holder, as a chip.
 *
 * Colour never carries the meaning on its own: each state is worded, and the suspended one carries
 * its date, because "when did this designer lose access" is the question the row is usually opened
 * to answer. A suspended row with no date would read as a bug in the screen rather than as a state,
 * which is exactly why the API stamps `revokedAt` even on a row created suspended.
 */
function StandingChip({ entry }: { entry: DesignerRosterEntry }) {
  if (entry.isActive) {
    return (
      <span className="inline-flex items-center gap-1.5 rounded-full border border-success-600/25 bg-success-100 px-2.5 py-1 text-xs font-medium text-success-600">
        <BadgeCheck className="h-3.5 w-3.5" aria-hidden />
        May sign in
      </span>
    );
  }
  return (
    <span className="inline-flex flex-col gap-0.5">
      <span className="inline-flex w-fit items-center gap-1.5 rounded-full border border-error-600/25 bg-error-100 px-2.5 py-1 text-xs font-medium text-error-600">
        Suspended
      </span>
      <span className="text-xs text-ink-500">
        {entry.revokedAt ? `since ${formatDate(entry.revokedAt)}` : "date not recorded"}
      </span>
    </span>
  );
}

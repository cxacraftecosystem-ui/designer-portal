"use client";

import { Suspense, useCallback, useEffect, useRef, useState } from "react";
import Link from "next/link";
import { useSearchParams } from "next/navigation";
import { BadgeCheck, Clock, MailPlus, ShieldCheck, UserCheck, UserX } from "lucide-react";

import { OpenableRow } from "@/components/data/OpenableRow";
import { EmptyState } from "@/components/EmptyState";
import { Field, TextArea, TextInput } from "@/components/FormControls";
import { Dropdown } from "@/components/ui/Dropdown";
import { FieldLabelProvider } from "@/components/ui/fieldLabel";
import { PageHeader } from "@/components/PageHeader";
import { RecordDetailsDialog } from "@/components/records/RecordDetailsDialog";
import { Pagination } from "@/components/Pagination";
import { ResizableTh } from "@/components/ResizableTh";
import { RestrictedPanel } from "@/components/settings/RestrictedPanel";
import { RowActions, rowAction } from "@/components/RowActions";
import { useAuth } from "@/components/AuthProvider";
import { useConfirm } from "@/components/dialogs/ConfirmDialog";
import { refreshPendingAccessCount, usePendingAccessCount } from "@/components/hooks/usePendingAccessCount";
import { RosterFilterBar } from "@/components/admin/RosterFilterBar";
import { SortableTh, type RosterSortControl } from "@/components/admin/SortableTh";
import {
  clearRosterFilters,
  hasActiveRosterFilters,
  rosterFiltersFromSearchParams,
  type RosterFilters
} from "@/components/admin/rosterFilters";
import { CappedListNotice } from "@/components/data/CappedListNotice";
import { CUT_NOTICE_LIVE_REGION, searchCutNotice } from "@/components/data/cappedList";
import { ApiError } from "@/lib/api";
import {
  accessEntryDetails,
  addToAccessRoster,
  decideAccessRequest,
  listAccessRoster,
  promotionHeldSentence,
  suspendAccessEntry,
  updateAccessEntry,
  type AccessRosterEntry,
  type AccessRosterPage
} from "@/lib/accessRoster";
import { formatDate, formatDateTime } from "@/lib/format";
import { assignableRoles, canManageAccessRoster, roleLabel } from "@/lib/permissions";
import { MAX_PASSWORD_LENGTH, MIN_PASSWORD_LENGTH, passwordRuleLine } from "@/lib/signIn";
import type { UserRole } from "@/lib/types";
import { passwordAccountHref } from "@/app/(protected)/users/accountAdmin";
import { Toggle } from "@/components/ui/Toggle";
import {
  ACCESS_LIST_STALE_NOTE,
  ACCESS_LIST_UNREADABLE_BODY,
  ACCESS_LIST_UNREADABLE_TITLE,
  ACCESS_NOBODY_YET_BODY,
  ACCESS_NOBODY_YET_TITLE,
  ACCESS_NO_MATCH_BODY,
  ACCESS_NO_MATCH_TITLE,
  ACCESS_PAST_END_TITLE,
  accessRoleCutNotice,
  accessRosterHref,
  listFilteredAccessRoster,
  type AccessRosterAnswer
} from "./rosterQuery";
import { ActionGroup } from "@/components/ui/ActionGroup";

const NEW_ACCOUNTS_ONLY = "New accounts only. To change an existing account, use Manage users.";

const PAGE_SIZE = 20;

const QUEUE_PAGE_SIZE = 8;

const KIND = "access" as const;

export default function PlatformAccessPage() {
  return (
    <Suspense
      fallback={
        <PageHeader
          title="Who may sign in"
          icon={<ShieldCheck className="h-5 w-5" aria-hidden />}
        />
      }
    >
      <PlatformAccessScreen />
    </Suspense>
  );
}

function PlatformAccessScreen() {
  const { user } = useAuth();
  const confirm = useConfirm();
  const permitted = canManageAccessRoster(user);

  const counted = usePendingAccessCount(permitted);

  const searchParams = useSearchParams();
  const [filters, setFilters] = useState<RosterFilters>(() =>
    rosterFiltersFromSearchParams(KIND, new URLSearchParams(searchParams.toString()))
  );

  const [queue, setQueue] = useState<AccessRosterPage | null>(null);
  const [queuePage, setQueuePage] = useState(1);
  const [data, setData] = useState<AccessRosterAnswer | null>(null);
  const [page, setPage] = useState(1);
  const [loadFailed, setLoadFailed] = useState(false);
  const [searchPending, setSearchPending] = useState(false);
  const [editing, setEditing] = useState<AccessRosterEntry | null>(null);
  const [viewing, setViewing] = useState<AccessRosterEntry | null>(null);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [passwordAccount, setPasswordAccount] = useState<{
    notice: string;
    href: string;
    admitRole: UserRole | null;
  } | null>(null);
  const [heldPromotion, setHeldPromotion] = useState<string | null>(null);
  const formRef = useRef<HTMLFormElement | null>(null);

  const generation = useRef(0);
  const queueGeneration = useRef(0);

  const loadQueue = useCallback(async () => {
    if (!permitted) return;
    const mine = ++queueGeneration.current;
    try {
      const result = await listAccessRoster({ page: queuePage, pageSize: QUEUE_PAGE_SIZE, status: "PENDING" });
      if (mine !== queueGeneration.current) return;
      setQueue(result);
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
      const result = await listFilteredAccessRoster({ page, pageSize: PAGE_SIZE, filters });
      if (mine !== generation.current) return;
      setData(result);
      setLoadFailed(false);
      setError(null);
      if (result.items.length === 0 && result.pages > 0 && page > result.pages) setPage(result.pages);
    } catch (err) {
      if (mine !== generation.current) return;
      setError(err instanceof Error ? err.message : "Unable to load the access list");
      setLoadFailed(true);
    }
  }, [filters, page, permitted]);

  useEffect(() => {
    load();
  }, [load]);

  useEffect(() => {
    loadQueue();
  }, [loadQueue]);

  const applyFilters = useCallback((next: RosterFilters) => {
    setFilters(next);
    setPage(1);
  }, []);

  useEffect(() => {
    window.history.replaceState(null, "", accessRosterHref(filters));
  }, [filters]);

  const reloadAll = useCallback(async () => {
    await Promise.all([load(), loadQueue(), refreshPendingAccessCount()]);
  }, [load, loadQueue]);

  async function submit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const formElement = event.currentTarget;
    const form = new FormData(formElement);
    const email = String(form.get("email") ?? "").trim();
    const fullName = String(form.get("fullName") ?? "").trim();
    const notes = String(form.get("notes") ?? "").trim();
    const role = String(form.get("role") ?? "").trim();
    const withPassword = !editing && form.get("signInWith") === "PASSWORD";
    const password = withPassword ? String(form.get("password") ?? "") : "";
    const mustChange = form.get("mustChangePassword") === "on";
    if (!email) return;
    if (withPassword && !fullName) {
      setError("Enter their full name.");
      return;
    }

    setSaving(true);
    setError(null);
    setNotice(null);
    try {
      if (editing) {
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
        notes: notes || null,
        ...(withPassword ? { password, mustChangePassword: mustChange } : {})
      });
      if (withPassword) {
        setNotice(
          `${created.email} can sign in with your password${mustChange ? " and will then choose their own" : ""}.`
        );
        setPasswordAccount(null);
        formElement.reset();
        await reloadAll();
        return;
      }
      const held = promotionHeldSentence(created);
      if (held) {
        setHeldPromotion(held);
        setNotice(held);
        setPasswordAccount(null);
        formElement.reset();
        await reloadAll();
        return;
      }
      const message = `${created.email} may sign in with Google, as ${roleLabel(created.admitRole) || "the default role"}. The account is created the first time they sign in with Google.`;
      setNotice(message);
      setPasswordAccount({
        notice: message,
        href: passwordAccountHref(created.email, created.admitRole, created.fullName),
        admitRole: created.admitRole
      });
      formElement.reset();
      await reloadAll();
    } catch (err) {
      setError(err instanceof Error ? err.message : "Unable to add this address");
      if (err instanceof ApiError && err.status === 409) {
        applyFilters({ ...clearRosterFilters(KIND, filters), search: email });
      }
    } finally {
      setSaving(false);
    }
  }

  async function approve(entry: AccessRosterEntry, role: UserRole | "") {
    const ok = await confirm({
      title: `Let ${entry.email} in?`,
      body: role
        ? `They can sign in now, as ${roleLabel(role)}.`
        : "They can sign in now, with the default role.",
      note: NEW_ACCOUNTS_ONLY,
      confirmLabel: "Approve",
      tone: "warning"
    });
    if (!ok) return;
    try {
      const updated = await decideAccessRequest(entry.id, { decision: "APPROVE", role: role || null });
      const held = promotionHeldSentence(updated);
      setHeldPromotion(held);
      setNotice(held ?? `${updated.email} may sign in from their next attempt.`);
      setError(null);
      await reloadAll();
    } catch (err) {
      setError(err instanceof Error ? err.message : "Unable to approve this request");
    }
  }

  async function reject(entry: AccessRosterEntry) {
    const ok = await confirm({
      title: `Refuse ${entry.email}?`,
      body:
        "They're told it wasn't approved. Any session they are in now ends with it.",
      note:
        "The entry stays; you can approve them later.",
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
      body:
        "They can't sign in again. Any session they are in now ends immediately.",
      note:
        "The entry is kept. Approve again to restore access.",
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
        ? `Their joining date stays ${formatDate(entry.joinedAt)}.`
        : undefined,
      confirmLabel: "Restore",
      tone: "warning"
    });
    if (!ok) return;
    try {
      const updated = await decideAccessRequest(entry.id, { decision: "APPROVE" });
      const held = promotionHeldSentence(updated);
      setHeldPromotion(held);
      setNotice(held ?? `${updated.email} can sign in again.`);
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
    requestAnimationFrame(() => formRef.current?.scrollIntoView({ block: "start", behavior: "smooth" }));
  }

  const header = (
    <PageHeader
      title="Who may sign in"
      description="Who may sign in, and who is waiting."
      icon={<ShieldCheck className="h-5 w-5" aria-hidden />}
    />
  );

  if (!permitted) {
    return (
      <>
        {header}
        <RestrictedPanel
          title="Admin access required"
          body={
            `Admins only.`
          }
        />
      </>
    );
  }

  const grantable = assignableRoles(user);
  const queueRows = queue?.items ?? [];
  const rows = data?.items ?? [];

  const filtered = hasActiveRosterFilters(KIND, filters);

  const sortControl: RosterSortControl = { kind: KIND, filters, onChange: applyFilters };

  const emptySentence = searchCutNotice({
    noun: "entries",
    loaded: 0,
    total: data?.total ?? 0,
    term: filters.search,
    emptyLabel: filtered ? ACCESS_NO_MATCH_BODY : ACCESS_NOBODY_YET_BODY,
    pending: searchPending
  });

  const emptyTitle =
    data && data.total > 0
      ? ACCESS_PAST_END_TITLE
      : filtered
        ? ACCESS_NO_MATCH_TITLE
        : ACCESS_NOBODY_YET_TITLE;

  return (
    <>
      {header}

      <p className="mb-4 text-sm leading-6 text-ink-500">
        Designers are managed on the{" "}
        <Link href="/admin/designers" className="font-medium text-purple-700 dark:text-purple-300 hover:underline">
          designer roster
        </Link>
        .
      </p>

      {error ? (
        <div role="alert" className="mb-4 rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">
          {error}
        </div>
      ) : null}
      {notice ? (
        <div
          className={
            heldPromotion !== null && heldPromotion === notice
              ? "mb-4 rounded-md border border-amber-500/30 bg-amber-100 px-3 py-2 text-sm text-amber-800"
              : "mb-4 rounded-md border border-line-200 bg-surface-50 px-3 py-2 text-sm text-ink-700"
          }
        >
          {notice}
          {passwordAccount && passwordAccount.notice === notice ? (
            <>
              {" "}Using a password?{" "}
              <Link href={passwordAccount.href} className="font-medium text-purple-700 dark:text-purple-300 hover:underline">
                Create a password account
              </Link>
              {passwordAccount.admitRole
                ? ", with this address and role already filled in."
                : ", with this address filled in — choose their role there."}
            </>
          ) : null}
        </div>
      ) : null}

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
                  {queue.total}
                </span>
              ) : null}
            </h2>
            <p className="text-sm text-ink-500">
              Signed in but not on the list yet.
            </p>
          </div>
        </div>

        {counted?.capReached ? (
          <div className="border-b border-amber-500/30 bg-amber-100 px-4 py-2 text-xs leading-5 text-amber-900">
            Queue full ({counted.capacity}). New requests aren&apos;t recorded — decide some to make room.
          </div>
        ) : null}

        {queue === null ? (
          <div className="p-4 text-sm text-ink-700">Loading the approval queue…</div>
        ) : queueRows.length === 0 ? (
          <div className="p-4">
            <EmptyState
              title="Nobody is waiting"
              body="New sign-in requests appear here."
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
        key={editing?.id ?? "new"}
        className="panel mb-5 grid gap-4 p-4"
      >
        <div>
          <h2 className="font-display text-lg font-bold text-ink-900">
            {editing ? `Correct the entry for ${editing.email}` : "Let somebody in by address"}
          </h2>
          <p className="mt-1 text-sm leading-6 text-ink-muted">
            {editing ? (
              "For your records. Use Approve, Refuse, Suspend or Restore to change access."
            ) : (
              <>
                Choose how they sign in. Adding them here approves them. Password accounts can also be made on{" "}
                <Link href="/users" className="font-medium text-purple-700 dark:text-purple-300 hover:underline">
                  Manage users
                </Link>
                .
              </>
            )}
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
              readOnly={editing !== null}
              autoComplete="off"
            />
          </Field>
          <Field label="Full name">
            <TextInput name="fullName" maxLength={180} defaultValue={editing?.fullName ?? ""} />
          </Field>
          <Field label="Joins as">
            <AdmitTierField grantable={grantable} initial={editing?.admitRole ?? ""} />
            <span className="text-xs text-ink-500">{NEW_ACCOUNTS_ONLY}</span>
          </Field>
        </div>
        {editing ? null : <SignInMethodField />}
        <Field label="Note">
          <TextArea
            name="notes"
            maxLength={4000}
            rows={2}
            defaultValue={editing?.notes ?? ""}
            placeholder="Who they are and why they were admitted."
          />
        </Field>
        <ActionGroup>
          <button className="field-button" disabled={saving}>
            <MailPlus className="h-4 w-4" aria-hidden />
            {saving ? "Saving…" : editing ? "Update entry" : "Add to the list"}
          </button>
          {editing ? (
            <button type="button" className="field-button-secondary" onClick={() => setEditing(null)}>
              Cancel
            </button>
          ) : null}
        </ActionGroup>
      </form>

      <RosterFilterBar
        kind={KIND}
        filters={filters}
        onChange={applyFilters}
        onSearchPendingChange={setSearchPending}
      />

      <div {...CUT_NOTICE_LIVE_REGION}>
        <CappedListNotice
          cuts={[accessRoleCutNotice(data?.roleMatchTruncated)]}
          className="mb-4 grid gap-0.5"
        />
      </div>

      <section className="panel overflow-hidden">
        {loadFailed && data !== null ? (
          <div className="border-b border-amber-500/30 bg-amber-100 px-4 py-2 text-xs leading-5 text-amber-900">
            {ACCESS_LIST_STALE_NOTE}
          </div>
        ) : null}

        <div {...CUT_NOTICE_LIVE_REGION}>
          {data === null && !loadFailed ? (
            <div className="p-4 text-sm text-ink-700">Loading the list…</div>
          ) : data === null ? (
            <div className="p-4">
              <EmptyState title={ACCESS_LIST_UNREADABLE_TITLE} body={ACCESS_LIST_UNREADABLE_BODY} />
            </div>
          ) : rows.length === 0 && searchPending ? (
            <div className="p-4 text-sm text-ink-700">Searching…</div>
          ) : rows.length === 0 ? (
            <div className="p-4">
              <EmptyState title={emptyTitle} body={emptySentence} />
            </div>
          ) : null}
        </div>

        {rows.length === 0 ? null : (
          <div className="overflow-x-auto">
            <table className="w-full min-w-[1240px] text-left text-sm">
              <thead className="bg-surface-50 text-xs uppercase text-ink-500">
                <tr>
                  <SortableTh control={sortControl} column="name" label="Name" />
                  <SortableTh control={sortControl} column="email" label="Email" />
                  <SortableTh control={sortControl} column="standing" label="Standing" />
                  <SortableTh control={sortControl} column="added" label="Added" />
                  <SortableTh control={sortControl} column="joined" label="Joined" />
                  <SortableTh control={sortControl} column="firstSeen" label="First signed in" />
                  <SortableTh control={sortControl} column="attempts" label="Requests" />
                  <ResizableTh>Note</ResizableTh>
                  <ResizableTh className="text-right">Actions</ResizableTh>
                </tr>
              </thead>
              <tbody className="divide-y divide-line-200">
                {rows.map((entry) => (
                  <OpenableRow key={entry.id} onOpen={() => setViewing(entry)}>
                    <td className="px-4 py-3">
                      <button
                        type="button"
                        onClick={() => setViewing(entry)}
                        className="rounded-sm text-left font-medium text-ink-900 underline-offset-2 hover:underline"
                      >
                        {entry.fullName || <span className="text-ink-500">Name not recorded</span>}
                      </button>
                    </td>
                    <td className="px-4 py-3 text-ink-700"><span className="break-all">{entry.email}</span></td>
                    <td className="px-4 py-3">
                      <StandingChip entry={entry} />
                    </td>
                    <td className="px-4 py-3 text-ink-700">
                      {entry.createdAt ? formatDate(entry.createdAt) : "—"}
                    </td>
                    <td className="px-4 py-3 text-ink-700">
                      {entry.joinedAt ? formatDate(entry.joinedAt) : "—"}
                    </td>
                    <td className="px-4 py-3 text-ink-700">
                      {entry.firstSeenAt ? (
                        formatDate(entry.firstSeenAt)
                      ) : (
                        <span className="text-ink-500">
                          {entry.joinedAt ? "Has not signed in yet" : "Has never been admitted"}
                        </span>
                      )}
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
                          Update
                        </button>
                        {entry.status === "ACTIVE" ? (
                          <button type="button" data-danger className={rowAction("danger")} onClick={() => suspend(entry)}>
                            Suspend
                          </button>
                        ) : (
                          <button type="button" className={rowAction("positive")} onClick={() => restore(entry)}>
                            <UserCheck className="h-3.5 w-3.5" aria-hidden />
                            {entry.status === "PENDING" ? "Approve" : "Let back in"}
                          </button>
                        )}
                        {entry.status === "PENDING" ? (
                          <button type="button" data-danger className={rowAction("danger")} onClick={() => reject(entry)}>
                            Refuse
                          </button>
                        ) : null}
                      </RowActions>
                    </td>
                  </OpenableRow>
                ))}
              </tbody>
            </table>
          </div>
        )}
        {data ? <Pagination page={data.page} pages={data.pages} total={data.total} onPage={setPage} /> : null}
      </section>
      <RecordDetailsDialog
        open={viewing !== null}
        onClose={() => setViewing(null)}
        title={viewing?.fullName || "Name not recorded"}
        description={viewing?.email}
        details={viewing ? accessEntryDetails(viewing) : []}
        onUpdate={() => {
          const target = viewing;
          setViewing(null);
          if (target) startEdit(target);
        }}
      />
    </>
  );
}

function SignInMethodField() {
  const [method, setMethod] = useState<"PROVIDER" | "PASSWORD">("PROVIDER");
  const [showPassword, setShowPassword] = useState(false);
  return (
    <fieldset className="grid gap-2">
      <legend className="field-label mb-1">How will they sign in?</legend>
      <label className="flex items-center gap-2 text-sm text-ink-700">
        <input
          type="radio"
          name="signInWith"
          value="PROVIDER"
          checked={method === "PROVIDER"}
          onChange={() => setMethod("PROVIDER")}
        />
        With Google, Microsoft or Yahoo
      </label>
      <label className="flex items-center gap-2 text-sm text-ink-700">
        <input
          type="radio"
          name="signInWith"
          value="PASSWORD"
          checked={method === "PASSWORD"}
          onChange={() => setMethod("PASSWORD")}
        />
        With a password
      </label>
      {method === "PASSWORD" ? (
        <div className="grid gap-2 rounded-md border border-line-200 bg-surface-50 p-3 md:max-w-md">
          <label htmlFor="access-password" className="field-label">
            Password
          </label>
          <input
            id="access-password"
            name="password"
            type={showPassword ? "text" : "password"}
            autoComplete="new-password"
            required
            minLength={MIN_PASSWORD_LENGTH}
            maxLength={MAX_PASSWORD_LENGTH}
            className="field-input"
          />
          <Toggle label="Show the password" checked={showPassword} onChange={setShowPassword} />
          <Toggle name="mustChangePassword" defaultChecked label="Ask them to choose a new one at first sign-in" />
          <p className="text-xs leading-5 text-ink-500">
            {passwordRuleLine("Full name required.")}
          </p>
        </div>
      ) : null}
    </fieldset>
  );
}

function AdmitTierField({ grantable, initial }: { grantable: UserRole[]; initial: UserRole | "" }) {
  const [role, setRole] = useState<UserRole | "">(initial);
  return (
    <>
      <Dropdown
        value={role}
        onChange={(next) => setRole(next as UserRole | "")}
        options={[
          { value: "", label: "Default role (the lowest)" },
          ...grantable.map((option) => ({ value: option, label: roleLabel(option) }))
        ]}
      />
      <input type="hidden" name="role" value={role} />
    </>
  );
}

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
          {entry.requestedAt ? `Asked ${formatDateTime(entry.requestedAt)}` : "Asked at an unrecorded time"}
          {" · "}
          {entry.attemptCount} attempt{entry.attemptCount === 1 ? "" : "s"}
          {entry.lastAttemptAt ? `, last ${formatDateTime(entry.lastAttemptAt)}` : ""}
        </div>
      </div>
      <div className="flex flex-wrap items-center gap-2">
        <span id={`tier-label-${entry.id}`} className="sr-only">
          The role {entry.email} joins with
        </span>
        <FieldLabelProvider value={`tier-label-${entry.id}`}>
          <Dropdown
            value={role}
            onChange={(next) => setRole(next as UserRole | "")}
            options={[
              { value: "", label: "Joins with the default role" },
              ...grantable.map((option) => ({ value: option, label: `Joins as ${roleLabel(option)}` }))
            ]}
            advanceOnSelect={false}
            className="h-9 min-w-56 py-0 text-sm"
          />
        </FieldLabelProvider>
        <ActionGroup className="max-sm:w-full"><button type="button" className={rowAction("positive")} onClick={() => onApprove(role)}>
          <UserCheck className="h-3.5 w-3.5" aria-hidden />
          Approve
        </button>
        <button type="button" data-danger className={rowAction("danger")} onClick={onReject}>
          <UserX className="h-3.5 w-3.5" aria-hidden />
          Refuse
        </button></ActionGroup>
      </div>
    </li>
  );
}

function StandingChip({ entry }: { entry: AccessRosterEntry }) {
  if (entry.status === "ACTIVE") {
    return (
      <span className="inline-flex flex-col gap-0.5">
        <span className="inline-flex w-fit items-center gap-1.5 rounded-full border border-success-600/25 bg-success-100 px-2.5 py-1 text-xs font-medium text-success-600">
          <BadgeCheck className="h-3.5 w-3.5" aria-hidden />
          May sign in
        </span>
        <span className="text-xs text-ink-500">
          {entry.admitRole ? `as ${roleLabel(entry.admitRole)}` : "with the default role"}
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

"use client";

import { useEffect, useRef, useState } from "react";
import Link from "next/link";
import { ShieldCheck } from "lucide-react";

import { deleteConfirm, useConfirm } from "@/components/dialogs/ConfirmDialog";
import { EmptyState } from "@/components/EmptyState";
import { Field, Select, TextInput } from "@/components/FormControls";
import { PageHeader } from "@/components/PageHeader";
import { Pagination } from "@/components/Pagination";
import { ResizableTh } from "@/components/ResizableTh";
import { RowActions, rowAction } from "@/components/RowActions";
import { SearchInput } from "@/components/SearchInput";
import { adminChromeVisible, useAdminView } from "@/components/AdminViewProvider";
import { useAuth } from "@/components/AuthProvider";
import { apiFetch, listResource } from "@/lib/api";
import { requiredText } from "@/lib/forms";
import {
  assignableRoles,
  canManageDesignerRoster,
  canManageUser,
  hasRank,
  isAdmin,
  isMasterAdmin,
  roleLabel
} from "@/lib/permissions";
import type { PageResult, User } from "@/lib/types";

function GrantCell({
  included,
  editable,
  label,
  onChange
}: {
  included: boolean;
  editable: boolean;
  label: string;
  onChange: (value: boolean) => void;
}) {
  return (
    <td className="px-4 py-3 text-ink-700">
      <label className="inline-flex items-center gap-2">
        <input type="checkbox" checked={included} disabled={!editable} aria-label={label} onChange={(event) => onChange(event.target.checked)} />
        <span>{included ? "Yes" : "No"}</span>
      </label>
    </td>
  );
}

export default function UsersPage() {
  const confirm = useConfirm();
  const { user: currentUser } = useAuth();
  const { adminMode } = useAdminView();
  /**
   * Professors reach this page too, with promotion rights only: the role column is editable for
   * users beneath them, while creation, deletion, capability grants and the bulk panel stay admin+.
   *
   * `adminChromeVisible` is the admin-view half. For an ADMIN with admin view off AppShell already
   * replaces the whole route (the nav link is hidden, so the page would otherwise be reachable only
   * by URL) — this is the second line, keeping the admin-only controls tied to the toggle wherever
   * the page renders. It cannot narrow a PROFESSOR: they are not `isAdmin`, so `adminChromeVisible`
   * is true for them and nothing here changes, which matters because they have no toggle to undo.
   */
  const chrome = adminChromeVisible(currentUser, adminMode);
  const admin = isAdmin(currentUser) && chrome;
  const masterControls = isMasterAdmin(currentUser) && chrome;
  const [data, setData] = useState<PageResult<User> | null>(null);
  const [query, setQuery] = useState("");
  const [applied, setApplied] = useState("");
  const [page, setPage] = useState(1);
  const [error, setError] = useState<string | null>(null);
  const skipFirstDebounce = useRef(true);
  // The master-admin bulk panel picks from EVERY user, not just the page of the table above it.
  const [allUsers, setAllUsers] = useState<User[]>([]);
  const [adminSelection, setAdminSelection] = useState<Set<string>>(new Set());
  const [grantAdmin, setGrantAdmin] = useState(true);
  const [grantQuestionnaire, setGrantQuestionnaire] = useState(false);
  const [grantDataset, setGrantDataset] = useState(false);
  const [applying, setApplying] = useState(false);
  const [adminMessage, setAdminMessage] = useState<string | null>(null);

  function toggleAdminSelection(id: string) {
    setAdminSelection((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  }

  async function applyAdminGrants() {
    if (adminSelection.size === 0) return;
    setApplying(true);
    setAdminMessage(null);
    setError(null);
    try {
      // Additive grants only: checked privileges are granted, unchecked ones are left untouched
      // (revoke individually via the per-user table above). This keeps the bulk action safe.
      const body: Record<string, unknown> = {};
      if (grantAdmin) body.role = "ADMIN";
      if (grantQuestionnaire) body.canManageQuestionnaire = true;
      if (grantDataset) body.canDownloadDataset = true;
      if (Object.keys(body).length === 0) {
        setAdminMessage("Pick at least one of: admin access, questionnaire or dataset download to grant.");
        setApplying(false);
        return;
      }
      const ids = Array.from(adminSelection);
      for (const id of ids) {
        await apiFetch(`/users/${id}`, { method: "PATCH", body: JSON.stringify(body) });
      }
      setAdminMessage(`Updated ${ids.length} user${ids.length === 1 ? "" : "s"}.`);
      setAdminSelection(new Set());
      load();
      loadAllUsers();
    } catch (err) {
      setError(err instanceof Error ? err.message : "Unable to update admin access");
    } finally {
      setApplying(false);
    }
  }

  async function load() {
    try {
      setData(await listResource<User>("/users", { search: applied || undefined, page, pageSize: 20 }));
      setError(null);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Unable to load users");
    }
  }

  /** Full roster for the bulk grants panel — the paged table alone would hide most accounts. */
  async function loadAllUsers() {
    if (!masterControls) return;
    try {
      const result = await listResource<User>("/users", { pageSize: 100 });
      setAllUsers(result.items);
    } catch {
      // The bulk panel degrades to whatever is already loaded; the table above still works.
    }
  }

  useEffect(() => {
    load();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [page, applied]);

  useEffect(() => {
    loadAllUsers();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [currentUser?.id, masterControls]);

  // Live search: debounce typing by 350ms; Enter applies immediately via onSubmit.
  useEffect(() => {
    if (skipFirstDebounce.current) {
      skipFirstDebounce.current = false;
      return;
    }
    const timer = setTimeout(() => {
      setApplied(query);
      setPage(1);
    }, 350);
    return () => clearTimeout(timer);
  }, [query]);

  async function submit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    // Capture the element before awaiting: React nulls currentTarget after dispatch.
    const formElement = event.currentTarget;
    const form = new FormData(formElement);
    try {
      await apiFetch("/users", {
        method: "POST",
        body: JSON.stringify({
          name: requiredText(form, "name"),
          email: requiredText(form, "email"),
          password: requiredText(form, "password"),
          role: requiredText(form, "role"),
          canManageQuestionnaire: form.get("canManageQuestionnaire") === "on",
          canDownloadDataset: form.get("canDownloadDataset") === "on"
        })
      });
      formElement.reset();
      load();
    } catch (err) {
      setError(err instanceof Error ? err.message : "Unable to create user");
    }
  }

  async function updateRole(user: User, role: string) {
    try {
      await apiFetch(`/users/${user.id}`, { method: "PATCH", body: JSON.stringify({ role }) });
      setError(null);
      load();
    } catch (err) {
      setError(err instanceof Error ? err.message : "Unable to change role");
    }
  }

  async function updateGrant(
    user: User,
    field: "canManageQuestionnaire" | "canDownloadDataset",
    value: boolean
  ) {
    try {
      await apiFetch(`/users/${user.id}`, { method: "PATCH", body: JSON.stringify({ [field]: value }) });
      load();
    } catch (err) {
      setError(err instanceof Error ? err.message : "Unable to update access");
    }
  }

  async function remove(user: User) {
    const ok = await confirm(
      deleteConfirm(
        "Delete this account?",
        <>
          <span className="font-medium text-ink-900">{user.name || user.email}</span> loses access immediately and
          cannot sign in again. This action cannot be undone.
        </>,
        "Records they documented stay in the repository, still attributed to them."
      )
    );
    if (!ok) return;
    try {
      await apiFetch(`/users/${user.id}`, { method: "DELETE" });
      setError(null);
      load();
    } catch (err) {
      setError(err instanceof Error ? err.message : "Unable to delete user");
    }
  }

  return (
    <>
      <PageHeader
        title="Users"
        description="User management for professors and administrators — promotions for professors, full account control for admins."
        icon={<ShieldCheck className="h-5 w-5" aria-hidden />}
      />
      {error ? <div className="mb-4 rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">{error}</div> : null}
      {admin ? (
      <form onSubmit={submit} className="panel mb-5 grid gap-3 p-4 md:grid-cols-5">
        <Field label="Name" required>
          <TextInput name="name" required />
        </Field>
        <Field label="Email" required>
          <TextInput name="email" type="email" required />
        </Field>
        <Field label="Password" required>
          <TextInput name="password" type="password" minLength={8} required />
        </Field>
        <Field label="Role">
          <Select name="role" defaultValue="RESEARCHER">
            {assignableRoles(currentUser)
              .filter((role) => role !== "MASTER_ADMIN" || isMasterAdmin(currentUser))
              .map((role) => (
                <option key={role} value={role}>
                  {roleLabel(role)}
                </option>
              ))}
          </Select>
        </Field>
        <div className="md:col-span-5 flex flex-wrap gap-2">
          <label className="flex items-center gap-2 rounded-md border border-line-200 bg-field-100 px-3 py-2 text-sm text-ink-muted">
            <input name="canManageQuestionnaire" type="checkbox" />
            Manage questionnaire
          </label>
          {/* Crafts and workshops are NOT here, deliberately. They are rank-only now
              (require_craft_manager / require_workshop_manager = Professor and above), so a
              checkbox for them would set a column nothing reads — a control that looks like it
              grants access and does not is worse than no control at all. Promote the person
              instead. */}
          <label className="flex items-center gap-2 rounded-md border border-line-200 bg-field-100 px-3 py-2 text-sm text-ink-muted">
            <input name="canDownloadDataset" type="checkbox" />
            Download dataset
          </label>
        </div>
        <div className="md:col-span-5">
          <button className="field-button">Create user</button>
        </div>
      </form>
      ) : null}
      <p className="mb-4 text-xs text-ink-muted">
        {/* SEVEN, not six. Designer was added at rank 35, in the gap the original tens left, so it
            sits between Researcher and Professor — and this sentence is the one place in the app
            that spells the ladder out in prose rather than deriving it from ROLE_RANK, which is
            exactly why it was left saying "six" and listing one tier too few. */}
        Seven tiers: Master Admin → Admin → Professor → Designer → Researcher → Field Contributor →
        Crowdsource Volunteer. You can promote users to your own tier and below, and manage only users
        beneath your tier. Professors hold promotion rights only: they may change the role of anyone ranked
        beneath them (up to Professor) but cannot create or delete accounts or grant capabilities —
        those remain admin actions. Professors and above hold every capability implicitly; the
        checkboxes lift a single capability for a lower tier.
      </p>
      <p className="mb-4 text-xs text-ink-muted">
        {/* THE ONE TRAP ON THIS PAGE THAT COSTS SOMEBODY A WORKING DAY. `require_signin_allowed` in
            backend/app/api/routes/auth.py refuses a DESIGNER whose email has no ACTIVE roster row,
            with a 403 at sign-in — so setting this role and stopping there produces an account that
            looks correct in the table below and cannot log in, and the person refused has no way to
            discover why. Said here rather than left to be found: this page is where the role is
            handed out, so it is where the second half of the act has to be named. */}
        <span className="font-medium text-ink-700">Designer is the one role with a second half.</span> An account holding
        it may only sign in while its email address is on the designer roster and not suspended — the role grants the
        powers, the roster is the institution recognising the person. Promote somebody to Designer without empanelling
        that address and their next sign-in is refused, with nothing in this table saying so.{" "}
        {canManageDesignerRoster(currentUser) ? (
          <Link href="/admin/designers" className="font-medium text-purple-700 underline-offset-2 hover:underline">
            Open the designer roster
          </Link>
        ) : (
          <span>Ask an admin to add the address to the roster.</span>
        )}
      </p>
      <p className="mb-4 text-xs text-ink-muted">
        Two things are decided by RANK alone and have no checkbox: adding or editing crafts and
        workshops (Professor and above; deleting either is admin-only), and creating artisans,
        products, tools, processes and interviews (Researcher and above). Field contributors and
        volunteers keep answering existing interviews, uploading media and commenting. To give
        someone one of these, promote them.
      </p>
      <div className="mb-4">
        <SearchInput
          value={query}
          onChange={setQuery}
          onSubmit={() => {
            setApplied(query);
            setPage(1);
          }}
          placeholder="Search users by name or email"
        />
      </div>
      <section className="panel overflow-hidden">
        {!data ? (
          <div className="p-4 text-sm text-ink-700">Loading...</div>
        ) : data.items.length === 0 ? (
          <div className="p-4">
            <EmptyState title="No users found" />
          </div>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full min-w-[1000px] text-left text-sm">
              <thead className="bg-surface-50 text-xs uppercase text-ink-500">
                <tr>
                  <ResizableTh>Name</ResizableTh>
                  <ResizableTh>Email</ResizableTh>
                  <ResizableTh>Role</ResizableTh>
                  <ResizableTh>Questionnaire</ResizableTh>
                  <ResizableTh>Dataset</ResizableTh>
                  <ResizableTh>Provider</ResizableTh>
                  <ResizableTh className="text-right">Actions</ResizableTh>
                </tr>
              </thead>
              <tbody className="divide-y divide-line-200">
                {data.items.map((user) => (
                  <tr key={user.id}>
                    <td className="px-4 py-3 font-medium text-ink-900">{user.name}</td>
                    <td className="px-4 py-3 text-ink-700">{user.email}</td>
                    <td className="px-4 py-3">
                      {/* A native <select>, and no filter box: `assignableRoles` is the role ladder
                          from `lib/permissions` — seven rungs at most, fewer for anybody below master
                          admin — which is a fixed vocabulary of the kind the option-count rule
                          deliberately leaves plain. It is also rendered once per table row, and a
                          themed popover per row on a twenty-row page is twenty portalled panels for
                          a list of five words.

                          WHAT IS *NOT* SETTLED HERE, so the next reader does not read this comment as
                          a defence of everything about the control: this is a PERMISSION surface
                          rendered outside the themed register while the same page's "role for new
                          user" picker at the top uses `Select`. One page, one question, two controls.
                          That inconsistency is worth closing, on those grounds and not on
                          searchability — which is why it is named rather than quietly left. */}
                      {canManageUser(currentUser, user) && user.role !== "MASTER_ADMIN" ? (
                        <select className="field-input max-w-44" value={user.role} onChange={(event) => updateRole(user, event.target.value)}>
                          {assignableRoles(currentUser)
                            .filter((role) => role !== "MASTER_ADMIN" || isMasterAdmin(currentUser))
                            .map((role) => (
                              <option key={role} value={role}>
                                {roleLabel(role)}
                              </option>
                            ))}
                        </select>
                      ) : (
                        <span className="text-sm text-ink-700">{roleLabel(user.role)}</span>
                      )}
                    </td>
                    <GrantCell
                      included={hasRank(user, "PROFESSOR") || !!user.canManageQuestionnaire}
                      editable={admin && canManageUser(currentUser, user) && !hasRank(user, "PROFESSOR")}
                      label={`Allow ${user.email} to manage questionnaire`}
                      onChange={(value) => updateGrant(user, "canManageQuestionnaire", value)}
                    />
                    {/* No Crafts or Workshops columns: both are rank-only now, so the cell could
                        only ever have restated the role beside it or offered a toggle that changed
                        nothing. Read the Role column for those two. */}
                    <GrantCell
                      included={hasRank(user, "PROFESSOR") || !!user.canDownloadDataset}
                      editable={admin && canManageUser(currentUser, user) && !hasRank(user, "PROFESSOR")}
                      label={`Allow ${user.email} to download the entire dataset`}
                      onChange={(value) => updateGrant(user, "canDownloadDataset", value)}
                    />
                    <td className="px-4 py-3 text-ink-700">{user.authProvider ?? "-"}</td>
                    <td className="px-4 py-3 text-right">
                      {user.role === "MASTER_ADMIN" ? (
                        <span className="text-xs font-semibold text-amber-700">Protected</span>
                      ) : admin && canManageUser(currentUser, user) ? (
                        <RowActions>
                          <button className={rowAction("danger")} onClick={() => remove(user)}>
                            Delete
                          </button>
                        </RowActions>
                      ) : canManageUser(currentUser, user) ? (
                        <span className="text-xs text-ink-300">Role only</span>
                      ) : (
                        <span className="text-xs text-ink-300">Peer tier</span>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
        {data ? <Pagination page={data.page} pages={data.pages} total={data.total} onPage={setPage} /> : null}
      </section>
      {masterControls ? (
        <section className="panel mt-6 p-4">
          <h2 className="font-display font-bold text-xl text-ink">Admin management</h2>
          <p className="mt-1 text-sm text-ink-muted">
            Select one or more existing users, then grant them administrator access and decide their individual privileges in one action. Privileges are additive here — revoke individually in the table above.
          </p>
          {adminMessage ? <div className="mt-3 rounded-md border border-emerald-200 bg-emerald-50 px-3 py-2 text-sm text-emerald-700">{adminMessage}</div> : null}
          <div className="mt-4 grid gap-4 lg:grid-cols-[1.4fr_1fr]">
            <div className="rounded-md border border-line-200 bg-field-50 p-3">
              <div className="mb-2 text-xs font-semibold uppercase tracking-wide text-ink-soft">Select users ({adminSelection.size} selected)</div>
              <div className="grid max-h-72 gap-1 overflow-y-auto">
                {allUsers
                  .filter((user) => user.role !== "MASTER_ADMIN")
                  .map((user) => (
                    <label key={user.id} className="flex items-center gap-2 rounded px-2 py-1 hover:bg-field-100">
                      <input type="checkbox" checked={adminSelection.has(user.id)} onChange={() => toggleAdminSelection(user.id)} />
                      <span className="min-w-0 flex-1 truncate text-sm text-ink">
                        {user.name} <span className="text-ink-muted">· {user.email}</span>
                      </span>
                      <span className="rounded-full bg-field-200 px-2 py-0.5 text-xs text-ink-muted">{roleLabel(user.role)}</span>
                    </label>
                  ))}
                {allUsers.filter((user) => user.role !== "MASTER_ADMIN").length === 0 ? (
                  <p className="px-2 py-1 text-sm text-ink-muted">No eligible users.</p>
                ) : null}
              </div>
            </div>
            <div className="grid content-start gap-2 rounded-md border border-line-200 bg-field-50 p-3">
              <div className="mb-1 text-xs font-semibold uppercase tracking-wide text-ink-soft">Grant</div>
              <label className="flex items-center gap-2 text-sm text-ink">
                <input type="checkbox" checked={grantAdmin} onChange={(event) => setGrantAdmin(event.target.checked)} />
                Administrator access (role = ADMIN)
              </label>
              <label className="flex items-center gap-2 text-sm text-ink">
                <input type="checkbox" checked={grantQuestionnaire} onChange={(event) => setGrantQuestionnaire(event.target.checked)} />
                Manage questionnaire
              </label>
              <label className="flex items-center gap-2 text-sm text-ink">
                <input type="checkbox" checked={grantDataset} onChange={(event) => setGrantDataset(event.target.checked)} />
                Download dataset
              </label>
              <button
                type="button"
                className="field-button mt-2"
                disabled={applying || adminSelection.size === 0}
                onClick={applyAdminGrants}
              >
                {applying ? "Applying..." : `Apply to ${adminSelection.size} user${adminSelection.size === 1 ? "" : "s"}`}
              </button>
            </div>
          </div>
        </section>
      ) : null}
    </>
  );
}

"use client";

import { Suspense, useEffect, useRef, useState } from "react";
import Link from "next/link";
import { useSearchParams } from "next/navigation";
import { ShieldCheck, UserPlus } from "lucide-react";

import { deleteConfirm, useConfirm } from "@/components/dialogs/ConfirmDialog";
import { EmptyState } from "@/components/EmptyState";
import { Field, Select, TextInput } from "@/components/FormControls";
import { PageHeader } from "@/components/PageHeader";
import { Pagination } from "@/components/Pagination";
import { ResizableTh } from "@/components/ResizableTh";
import { RowActions, rowAction } from "@/components/RowActions";
import { SearchInput } from "@/components/SearchInput";
import { PasswordRevealButton } from "@/components/ui/PasswordReveal";
import { adminChromeVisible, useAdminView } from "@/components/AdminViewProvider";
import { useAuth } from "@/components/AuthProvider";
import { LIST_PAGE_CEILING } from "@/components/data/cappedList";
import { apiFetch, listResource } from "@/lib/api";
import { formatDate } from "@/lib/format";
import { fetchNotificationPreferences } from "@/lib/notifications";
import { requiredText } from "@/lib/forms";
import {
  ROLES_BY_RANK,
  assignableRoles,
  canManageDesignerRoster,
  canProvisionAccounts,
  hasRank,
  isAdmin,
  isMasterAdmin,
  provisionableRoles,
  roleLabel
} from "@/lib/permissions";
import {
  MAX_PASSWORD_LENGTH,
  MIN_PASSWORD_LENGTH,
  createPasswordAccount,
  issuePasswordLink,
  revokePasswordLink,
  type IssuedPasswordLink,
  type PasswordLinkDelivery
} from "@/lib/signIn";
import type { PageResult, User, UserRole } from "@/lib/types";

import {
  ACCOUNT_STATUS_LABEL,
  NO_PREFILL,
  STANDING_LABEL,
  accountStatus,
  createFormPrefill,
  createFormRole,
  createdPanelLinkNote,
  createdPanelOffersLink,
  createsPeerAccount,
  hasPrefill,
  newAccountBody,
  passwordFrom,
  passwordHasEdgeSpace,
  provisionerTierPhrase,
  rowOffers,
  type AccountStatusKind,
  type CreateFormPrefill
} from "./accountAdmin";
import {
  AccountDetailsDialog,
  CAUTION_CLASS,
  EdgeSpaceNote,
  RequireChangeNote,
  RequirePasswordChangeDialog,
  TemporaryPasswordDialog
} from "./AccountDialogs";

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

/**
 * Tone per status. A WORD in every chip, always — colour is the second channel, never the only one.
 * The amber is the one an administrator may have to act on: the account's next sign-in leads only to
 * choosing a new password, whether a provisioner typed the present one or a change was required of
 * the owner's own (the footnote under the table says which two). Literal status colours on both
 * sides, as `StatusBadge` does, so the pairing holds in either theme.
 */
const STATUS_TONE: Record<AccountStatusKind, string> = {
  MUST_CHOOSE_NEW_PASSWORD: "border-amber-500/30 bg-amber-100 text-amber-800",
  GOOGLE_ONLY: "border-line-200 bg-surface-50 text-ink-700",
  NO_PASSWORD: "border-line-200 bg-surface-50 text-ink-700",
  NEVER_SIGNED_IN: "border-line-200 bg-surface-50 text-ink-700",
  ACTIVE: "border-success-600/25 bg-success-100 text-success-600"
};

function AccountStatusCell({ user }: { user: User }) {
  const kind = accountStatus(user);
  return (
    <td className="px-4 py-3">
      <span className="inline-flex flex-col gap-0.5">
        <span className={`inline-flex w-fit rounded-full border px-2.5 py-1 text-xs font-medium ${STATUS_TONE[kind]}`}>
          {ACCOUNT_STATUS_LABEL[kind]}
        </span>
        {user.firstLoginAt ? (
          <span className="text-xs text-ink-500">First signed in {formatDate(user.firstLoginAt)}</span>
        ) : null}
      </span>
    </td>
  );
}

/**
 * `useSearchParams` suspends under Next 16, and this page reads it once — the pre-filled create form
 * a link from "Who may sign in" carries. The fallback is the page's own header, so the frame does not
 * jump while the query string is read.
 */
export default function UsersPage() {
  return (
    <Suspense fallback={<PageHeader title="Users" icon={<ShieldCheck className="h-5 w-5" aria-hidden />} />}>
      <UsersScreen />
    </Suspense>
  );
}

function UsersScreen() {
  const confirm = useConfirm();
  const [showNewPassword, setShowNewPassword] = useState(false);
  /**
   * A password link an admin has just minted, held on screen until they dismiss it.
   *
   * **THE ONLY COPY OF THIS CREDENTIAL IS THE ONE ON SCREEN.** The server stores a SHA-256 of
   * the token and never the token, so nothing can hand it back a second time: dismissing this
   * panel loses the link and the remedy is to issue another. That is deliberate and it is why
   * the panel says so rather than being quietly dismissable.
   */
  const [issuedLink, setIssuedLink] = useState<{ user: User; link: IssuedPasswordLink } | null>(null);
  const [linkBusy, setLinkBusy] = useState(false);
  const [linkCopied, setLinkCopied] = useState(false);
  // Whether "E-mail a password link" is offered at all. False until the answer arrives, and false
  // for good on a deployment without mail: the choice is then simply not drawn.
  const [mailAvailable, setMailAvailable] = useState(false);
  const { user: currentUser } = useAuth();
  const { adminMode } = useAdminView();
  /**
   * THREE POWERS ON ONE PAGE, AND THEY ARE NOT NESTED BY RANK.
   *
   * Everybody who reaches /users (Professor and above, `canManageUsers`) changes the role of anybody
   * beneath them. `provision` adds creating password accounts and looking after them — a temporary
   * password, a forced change, a password link, a corrected name or address — and is the SET
   * `canProvisionAccounts`, which holds MINISTRY_ADMIN although that tier is not an admin. `admin`
   * adds deleting accounts and the capability checkboxes, and `masterControls` the bulk panel. A
   * ministry admin is therefore shown the create form and the password actions and is NOT shown
   * Delete or a single grant box: the server refuses both, and a control it refuses is not offered.
   *
   * `adminChromeVisible` is the admin-view half. For an ADMIN with admin view off AppShell already
   * replaces the whole route (the nav link is hidden, so the page would otherwise be reachable only
   * by URL) — this is the second line, keeping the admin-only controls tied to the toggle wherever
   * the page renders. It cannot narrow anybody who is not `isAdmin` — a professor, a director, a
   * ministry admin — because they have no toggle to undo it, so for them it is always true.
   */
  const chrome = adminChromeVisible(currentUser, adminMode);
  const provision = canProvisionAccounts(currentUser) && chrome;
  const admin = isAdmin(currentUser) && chrome;
  const masterControls = isMasterAdmin(currentUser) && chrome;
  const offeredRoles = provisionableRoles(currentUser);
  /**
   * THE ADDRESS BAR SEEDS THE CREATE FORM ONCE. Read in a lazy initialiser — so the first render
   * already carries a link's address and tier — and stripped from the bar right after, so a reload
   * or Back does not put an account that was just created back into the form. Cleared again by a
   * successful create, which is what lets the form come back empty.
   */
  const searchParams = useSearchParams();
  const [prefill, setPrefill] = useState<CreateFormPrefill>(() =>
    createFormPrefill(new URLSearchParams(searchParams.toString()), offeredRoles)
  );
  /**
   * The tier the new account is created at. HELD HERE rather than left to the uncontrolled form, for
   * two reasons that are both about saying something BEFORE the account exists: a link that named an
   * address and no tier opens it EMPTY and required (`createFormRole`), and choosing the reader's own
   * tier draws the sentence that they will not manage the account afterwards (`createsPeerAccount`).
   */
  const [role, setRole] = useState<UserRole | "">(() => createFormRole(prefill, offeredRoles));
  /** Bumped by a successful create so the uncontrolled form remounts empty, prefill included. */
  const [formGeneration, setFormGeneration] = useState(0);
  const [requireChange, setRequireChange] = useState(true);
  const [edgeSpace, setEdgeSpace] = useState(false);
  const [creating, setCreating] = useState(false);
  const [createError, setCreateError] = useState<string | null>(null);
  /**
   * The account just made, held on screen until dismissed so the creator is told what to hand over.
   * NEVER THE PASSWORD: the form is remounted empty the moment this is set, and nothing here keeps
   * the typed value — a page that echoed a temporary password back would be one more place it lives.
   */
  const [created, setCreated] = useState<{ account: User; mustChangePassword: boolean; signInAt: string } | null>(
    null
  );
  const [notice, setNotice] = useState<string | null>(null);
  const [temporary, setTemporary] = useState<{ open: boolean; user: User | null }>({ open: false, user: null });
  const [requiring, setRequiring] = useState<{ open: boolean; user: User | null }>({ open: false, user: null });
  const [details, setDetails] = useState<{ open: boolean; user: User | null }>({ open: false, user: null });
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
      // `LIST_PAGE_CEILING`, the server's own cap, by name rather than as a literal -- and
      // deliberately NOT `RENDER_CAP`: the bulk grants panel draws every account as its own
      // checkbox row, not through a dropdown, so there is no 80-row draw cap to align this with
      // and asking for 80 would simply hide twenty accounts that are reachable today.
      const result = await listResource<User>("/users", { pageSize: LIST_PAGE_CEILING });
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
    let live = true;
    void fetchNotificationPreferences().then((answer) => {
      if (live) setMailAvailable(answer.available);
    });
    return () => {
      live = false;
    };
  }, []);

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

  // The query string seeded `prefill` and is not this page's state after that; see `prefill`.
  useEffect(() => {
    if (hasPrefill(prefill)) window.history.replaceState(null, "", "/users");
  }, [prefill]);

  async function submit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    // Read before any await: React nulls `currentTarget` across one.
    const form = new FormData(event.currentTarget);
    // ONE REQUEST AT A TIME. A double-click used to send two creates; the server answers the second
    // with a 409, but a page that never sends it does not need the server to be right about a race.
    if (creating) return;
    // The picker is `required`, so the browser normally stops this first; said here as well because a
    // tier nobody chose must never reach the server as a default it would accept.
    if (!role) {
      setCreateError("Choose the tier this account is created at.");
      return;
    }
    const body = newAccountBody(
      {
        name: requiredText(form, "name"),
        email: requiredText(form, "email"),
        role,
        // EXACTLY AS TYPED, never through `requiredText`, which trims: a password stored trimmed
        // while its owner pastes the original is refused at sign-in for ever.
        password: passwordFrom(form),
        mustChangePassword: requireChange,
        canManageQuestionnaire: form.get("canManageQuestionnaire") === "on",
        canDownloadDataset: form.get("canDownloadDataset") === "on"
      },
      { admin }
    );
    setCreating(true);
    setCreateError(null);
    try {
      const account = await createPasswordAccount(body);
      setCreated({
        account,
        // The SERVER's answer, not the box: what the account now owes is what was stored.
        mustChangePassword: account.mustChangePassword ?? body.mustChangePassword,
        signInAt: `${window.location.origin}/login`
      });
      setIssuedLink(null);
      setNotice(null);
      // Remount the form empty — the typed password is gone with it — and drop the link's prefill.
      setPrefill(NO_PREFILL);
      setRole(createFormRole(NO_PREFILL, offeredRoles));
      setFormGeneration((value) => value + 1);
      setRequireChange(true);
      setEdgeSpace(false);
      setShowNewPassword(false);
      load();
    } catch (err) {
      setCreateError(err instanceof Error ? err.message : "Unable to create the account");
    } finally {
      setCreating(false);
    }
  }

  async function issueLink(user: User, delivery: PasswordLinkDelivery = "COPY_LINK") {
    setLinkBusy(true);
    setError(null);
    try {
      const link = await issuePasswordLink(user.id, delivery);
      setIssuedLink({ user, link });
      setLinkCopied(false);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Unable to issue a password link");
    } finally {
      setLinkBusy(false);
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

  // The panel's offer is the ROW's offer for the same account — see `createdPanelOffersLink`.
  const createdLinkOffered = created ? createdPanelOffersLink(currentUser, created.account, { provision, admin }) : false;
  const createdLinkNote = created ? createdPanelLinkNote(currentUser, created.account, { provision, admin }) : null;

  return (
    <>
      <PageHeader
        title="Users"
        description={`Promotions from Professor upward; creating password accounts and looking after their passwords for ${provisionerTierPhrase()}; deleting accounts and granting capabilities for admins alone.`}
        icon={<ShieldCheck className="h-5 w-5" aria-hidden />}
      />
      {error ? (
        <div role="alert" className="mb-4 rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">
          {error}
        </div>
      ) : null}
      {/* Mounted from the first render, so a notice that appears later is announced. */}
      <div aria-live="polite">
        {notice ? (
          <div className="mb-4 rounded-md border border-line-200 bg-surface-50 px-3 py-2 text-sm text-ink-700">{notice}</div>
        ) : null}
      </div>
      {issuedLink && issuedLink.link.deliveredBy === "EMAIL" ? (
        <div className="mb-4 grid gap-2 rounded-md border border-line-200 bg-field-50 px-3 py-3">
          <p className="text-sm font-medium text-ink-900">
            {issuedLink.link.purpose === "INVITE" ? "Invitation link" : "Password reset link"} e-mailed to{" "}
            {issuedLink.user.name} · {issuedLink.user.email}
          </p>
          <p className="text-xs leading-5 text-ink-500">
            It works once and expires {new Date(issuedLink.link.expiresAt).toLocaleString()}. Withdraw it if it went
            to the wrong address.
          </p>
          <div className="flex flex-wrap items-center gap-2">
            <button
              type="button"
              className="field-button-secondary"
              disabled={linkBusy}
              onClick={async () => {
                setLinkBusy(true);
                try {
                  await revokePasswordLink(issuedLink.link.id);
                  setIssuedLink(null);
                } catch (err) {
                  setError(err instanceof Error ? err.message : "Unable to withdraw the link");
                } finally {
                  setLinkBusy(false);
                }
              }}
            >
              Withdraw
            </button>
            <button type="button" className="field-button-secondary" onClick={() => setIssuedLink(null)}>
              Done
            </button>
          </div>
        </div>
      ) : issuedLink ? (
        <div className="mb-4 grid gap-2 rounded-md border border-line-200 bg-field-50 px-3 py-3">
          <p className="text-sm font-medium text-ink-900">
            {/* The SERVER chose the purpose — an invitation for an account with no password or one
                never signed into since it was made, a reset otherwise — and the reader is told which
                they are holding. */}
            {issuedLink.link.purpose === "INVITE" ? "Invitation link" : "Password reset link"} for{" "}
            {issuedLink.user.name} · {issuedLink.user.email}
          </p>
          <div className="flex flex-wrap items-center gap-2">
            <input
              readOnly
              value={issuedLink.link.link ?? ""}
              aria-label="Password link"
              onFocus={(event) => event.currentTarget.select()}
              className="field-input min-w-0 flex-1 font-mono text-xs"
            />
            <button
              type="button"
              className="field-button-secondary"
              onClick={async () => {
                try {
                  await navigator.clipboard.writeText(issuedLink.link.link ?? "");
                  setLinkCopied(true);
                } catch {
                  // Clipboard access can be refused outright (an insecure origin, a locked-down
                  // browser). SAY NOTHING RATHER THAN CLAIM SUCCESS — the link is on screen and
                  // selectable, which is the fallback, and a "Copied" that did not copy is the
                  // one outcome that loses the credential.
                  setLinkCopied(false);
                }
              }}
            >
              {linkCopied ? "Copied" : "Copy"}
            </button>
            <button
              type="button"
              className="field-button-secondary"
              disabled={linkBusy}
              onClick={async () => {
                setLinkBusy(true);
                try {
                  await revokePasswordLink(issuedLink.link.id);
                  setIssuedLink(null);
                  setLinkCopied(false);
                } catch (err) {
                  setError(err instanceof Error ? err.message : "Unable to withdraw the link");
                } finally {
                  setLinkBusy(false);
                }
              }}
            >
              Withdraw
            </button>
            <button
              type="button"
              className="field-button-secondary"
              onClick={() => {
                setIssuedLink(null);
                setLinkCopied(false);
              }}
            >
              Done
            </button>
          </div>
          {/* TERSE, per the owner's instruction of 2026-08-30 — but this sentence is not
              decoration: nothing can show this link again, and an admin who closes the panel
              without copying it has to issue another one. */}
          <p className="text-xs leading-5 text-ink-500">
            Copy it now and send it yourself — it is shown once, works once, and expires{" "}
            {new Date(issuedLink.link.expiresAt).toLocaleString()}.
          </p>
        </div>
      ) : null}
      {/*
        WHAT TO HAND OVER — and never the password itself. The form below has already been remounted
        empty, so the typed value is gone from the page by the time this is drawn; this panel names
        what the creator must pass on and how, and that it will not be shown again.
      */}
      <div aria-live="polite">
        {created ? (
          <section
            aria-labelledby="account-created-heading"
            className="mb-4 grid gap-2 rounded-md border border-line-200 bg-field-50 px-3 py-3"
          >
            <h2 id="account-created-heading" className="text-sm font-medium text-ink-900">
              Account created for {created.account.name} · {created.account.email}, as{" "}
              {roleLabel(created.account.role)}
            </h2>
            <p className="text-sm leading-6 text-ink-700">
              Hand them two things, and not in the same message: the address they sign in with,{" "}
              <span className="break-all font-medium text-ink-900">{created.account.email}</span>, and the password you
              typed — this page does not show it again. They sign in at{" "}
              <span className="break-all font-medium text-ink-900">{created.signInAt}</span>.
            </p>
            <p className="text-sm leading-6 text-ink-700">
              {created.mustChangePassword
                ? "At their first sign-in they choose their own password before they can do anything else, and the one you typed stops working."
                : "You did not require a new password, so the one you typed stays theirs until they change it."}
              {createdLinkOffered ? " Rather not pass a password on? A password link lets them choose their own instead." : null}
            </p>
            {/* An account at the creator's own tier is one the link route refuses them (403 every
                time), so the panel says why there is no button rather than offering one. */}
            {createdLinkNote ? <p className="text-sm leading-6 text-ink-700">{createdLinkNote}</p> : null}
            <div className="flex flex-wrap items-center gap-2">
              {createdLinkOffered ? (
                <button
                  type="button"
                  className="field-button-secondary"
                  disabled={linkBusy}
                  onClick={() => issueLink(created.account)}
                >
                  Issue a password link
                </button>
              ) : null}
              {createdLinkOffered && mailAvailable ? (
                <button
                  type="button"
                  className="field-button-secondary"
                  disabled={linkBusy}
                  onClick={() => issueLink(created.account, "EMAIL")}
                >
                  Send a password link by e-mail
                </button>
              ) : null}
              <button type="button" className="field-button-secondary" onClick={() => setCreated(null)}>
                Done
              </button>
            </div>
          </section>
        ) : null}
      </div>
      {provision ? (
        <form
          key={formGeneration}
          onSubmit={submit}
          aria-labelledby="create-account-heading"
          className="panel mb-5 grid gap-4 p-4"
        >
          <div>
            <h2 id="create-account-heading" className="font-display text-lg font-bold text-ink-900">
              Create a password account
            </h2>
            <p className="mt-1 text-sm leading-6 text-ink-muted">
              For somebody who will sign in with their email address and a password you set.{" "}
              {/* Two readers, two true sentences: an admin's create admits the address on "Who may
                  sign in" whatever stood there, while a ministry admin's is refused (409) for an
                  address an admin refused or suspended — overturning that is an admin's decision. */}
              {admin
                ? "Creating the account also admits the address on “Who may sign in”."
                : "Creating the account lets that address sign in too — except an address an admin has refused or suspended: only an admin can let that one back in."}
            </p>
          </div>
          <div className="grid gap-3 md:grid-cols-2 lg:grid-cols-4">
            {/* `autoComplete="off"` on both identity boxes and `new-password` on the secret: this is
                a form for SOMEBODY ELSE's account, and a browser that took it for a sign-in form
                would fill in the administrator's own address and password — creating an account
                whose temporary password is theirs. */}
            <Field label="Name" required>
              <TextInput name="name" required maxLength={160} autoComplete="off" defaultValue={prefill.name} />
            </Field>
            <Field label="Email" required>
              <TextInput name="email" type="email" required autoComplete="off" defaultValue={prefill.email} />
            </Field>
            <Field label="Password" required>
              {/*
                THE EYE, ADDED 2026-08-30. This box is where an administrator TYPES A PASSWORD FOR
                SOMEBODY ELSE and then has to read it out or message it — the one situation in which
                masking protects nobody and costs the person on the other end of the telephone a
                second attempt.
              */}
              <div className="relative">
                <TextInput
                  name="password"
                  type={showNewPassword ? "text" : "password"}
                  minLength={MIN_PASSWORD_LENGTH}
                  maxLength={MAX_PASSWORD_LENGTH}
                  required
                  autoComplete="new-password"
                  autoCapitalize="none"
                  autoCorrect="off"
                  spellCheck={false}
                  onChange={(event) => setEdgeSpace(passwordHasEdgeSpace(event.target.value))}
                  className="pr-11"
                />
                <PasswordRevealButton
                  revealed={showNewPassword}
                  onToggle={() => setShowNewPassword((value) => !value)}
                  size={18}
                />
              </div>
            </Field>
            <Field label="Role">
              {/* `provisionableRoles` — exactly what the server's `assert_role` accepts from this
                  account, and empty for anybody the create route refuses outright. Required, and
                  EMPTY when a link filled in an address but no tier this reader can create at: see
                  `createFormRole` for the Researcher a trusted link used to hand out. */}
              <Select
                name="role"
                value={role}
                onChange={(event) => setRole(event.target.value as UserRole)}
                required
                placeholder="Choose a tier"
              >
                {offeredRoles.map((option) => (
                  <option key={option} value={option}>
                    {roleLabel(option)}
                  </option>
                ))}
              </Select>
            </Field>
          </div>
          {role === "" && prefill.email ? (
            <p className="text-xs leading-5 text-ink-500">Choose the tier yourself: the link filled in the address, not the tier.</p>
          ) : null}
          {createsPeerAccount(currentUser, role) ? (
            // Said BEFORE the account exists: afterwards the row only reads "Peer tier".
            <p className={CAUTION_CLASS}>
              {isMasterAdmin(currentUser)
                ? "Master admins are peers: once this account exists, no master admin can set its password, issue it a link or correct it here."
                : "This is your own tier: once the account exists you cannot set its password, issue it a link or correct it — only a higher tier can."}
            </p>
          ) : null}
          {edgeSpace ? <EdgeSpaceNote /> : null}
          <div className="grid gap-2">
            {/* Ticked by default and SENT EITHER WAY: what the box says is what the account owes. */}
            <label className="flex items-start gap-2 text-sm text-ink-700">
              <input
                type="checkbox"
                checked={requireChange}
                onChange={(event) => setRequireChange(event.target.checked)}
                className="mt-1"
              />
              <span>Require a new password at first sign-in</span>
            </label>
            <RequireChangeNote required={requireChange} when="first" />
          </div>
          {admin ? (
            <div className="flex flex-wrap gap-2">
              <label className="flex items-center gap-2 rounded-md border border-line-200 bg-field-100 px-3 py-2 text-sm text-ink-muted">
                <input name="canManageQuestionnaire" type="checkbox" />
                Manage questionnaire
              </label>
              {/* Crafts and workshops are NOT here, deliberately. They are rank-only now
                  (require_craft_manager / require_workshop_manager = Professor and above), so a
                  checkbox for them would set a column nothing reads — a control that looks like it
                  grants access and does not is worse than no control at all. Promote the person
                  instead. And neither box is drawn for a provisioner who is not an admin: the server
                  refuses a ministry admin any grant, so offering one would only manufacture a 403. */}
              <label className="flex items-center gap-2 rounded-md border border-line-200 bg-field-100 px-3 py-2 text-sm text-ink-muted">
                <input name="canDownloadDataset" type="checkbox" />
                Download dataset
              </label>
            </div>
          ) : null}
          {createError ? (
            // Beside the button rather than at the top of the page: a 409 for an address that is
            // already an account, or one an admin has barred, is about THIS form.
            <div role="alert" className="rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">
              {createError}
            </div>
          ) : null}
          <div>
            <button className="field-button" disabled={creating}>
              <UserPlus className="h-4 w-4" aria-hidden />
              {creating ? "Creating…" : "Create account"}
            </button>
          </div>
        </form>
      ) : null}
      <p className="mb-4 text-xs text-ink-muted">
        {/* DERIVED, AND IT USED TO BE TYPED OUT. This was the one place in the app that spelled the
            ladder out in prose rather than reading ROLE_RANK — so it shipped saying "six" with one
            tier too few, was corrected by hand to "Seven tiers: … → Designer → …", and would have
            been wrong again the day INSPECTOR landed. A hand-written enumeration of an
            access-control ladder is a fact with no owner: nothing renders it wrong, there is simply
            one fewer name than there are roles. The count is `.length` and the names come from
            `ROLES_BY_RANK` (already highest-first) through `roleLabel`, so this sentence cannot
            disagree with the picker three lines below it that is built from the same array. */}
        {ROLES_BY_RANK.length} tiers: {ROLES_BY_RANK.map(roleLabel).join(" → ")}. You can promote
        users to your own tier and below, and manage only users beneath your tier. Every tier from
        Professor upward may change the role of anyone beneath it. {provisionerTierPhrase()} also create
        password accounts, set temporary passwords, issue password links and correct names and addresses;
        deleting an account, granting a capability and letting back in an address an admin refused or
        suspended remain admin actions. Professors and above hold every capability implicitly; the
        checkboxes lift a single capability for a lower tier.
      </p>
      <p className="mb-4 text-xs text-ink-muted">
        {/* CORRECTED 2026-10-09. This warned that promoting somebody to Designer without empanelling
            the address got their next sign-in refused, and cited a `require_signin_allowed` that no
            longer exists. Sign-in now empanels an admitted DESIGNER who has no roster row
            (`auth.login`, through `ensure_empanelled`), and `POST /api/users` does it at once — so the
            warning sent people on a needless second errand. What is still true, and still costs
            somebody a working day, is the exception: `ensure_empanelled` only ever CREATES, so a
            SUSPENDED empanelment stays suspended and the sign-in is refused until an admin restores
            it. */}
        <span className="font-medium text-ink-700">Designer is the one role with a second half: the designer roster.</span>{" "}
        An account made a designer here is empanelled for you — at once when it is created on this page, otherwise at its
        next sign-in. The exception is an address whose empanelment was suspended: it stays suspended, and that
        person&apos;s sign-in is refused until an admin restores it.{" "}
        {canManageDesignerRoster(currentUser) ? (
          <Link href="/admin/designers" className="font-medium text-purple-700 underline-offset-2 hover:underline">
            Open the designer roster
          </Link>
        ) : (
          <span>Ask an admin if somebody you made a designer cannot sign in.</span>
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
                  {/* "Sign-in" replaced "Provider". The raw LOCAL/GOOGLE enum answered a question
                      nobody here asks, and stopped answering it once a password account could also
                      be signed into with Google; the status says whether a temporary secret is
                      still live, and names a Google-only account as such. */}
                  <ResizableTh>Sign-in</ResizableTh>
                  <ResizableTh className="text-right">Actions</ResizableTh>
                </tr>
              </thead>
              <tbody className="divide-y divide-line-200">
                {data.items.map((user) => {
                  // ONE DECISION PER ROW, made in ./accountAdmin where a test can reach it.
                  const offers = rowOffers(currentUser, user, { provision, admin });
                  return (
                  <tr key={user.id}>
                    <td className="px-4 py-3 font-medium text-ink-900">{user.name}</td>
                    <td className="px-4 py-3 text-ink-700">{user.email}</td>
                    <td className="px-4 py-3">
                      {/* A native <select>, and no filter box: `assignableRoles` is the role ladder
                          from `lib/permissions` — the whole ladder at most, fewer for anybody below
                          master admin — which is a fixed vocabulary of the kind the option-count rule
                          deliberately leaves plain. It is also rendered once per table row, and a
                          themed popover per row on a twenty-row page is twenty portalled panels for
                          a list of a few words.

                          WHAT IS *NOT* SETTLED HERE, so the next reader does not read this comment as
                          a defence of everything about the control: this is a PERMISSION surface
                          rendered outside the themed register while the same page's "role for new
                          user" picker at the top uses `Select`. One page, one question, two controls.
                          That inconsistency is worth closing, on those grounds and not on
                          searchability — which is why it is named rather than quietly left. */}
                      {offers.changeRole ? (
                        <select
                          className="field-input max-w-44"
                          value={user.role}
                          aria-label={`Role of ${user.email}`}
                          onChange={(event) => updateRole(user, event.target.value)}
                        >
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
                      editable={offers.grants}
                      label={`Allow ${user.email} to manage questionnaire`}
                      onChange={(value) => updateGrant(user, "canManageQuestionnaire", value)}
                    />
                    {/* No Crafts or Workshops columns: both are rank-only now, so the cell could
                        only ever have restated the role beside it or offered a toggle that changed
                        nothing. Read the Role column for those two. */}
                    <GrantCell
                      included={hasRank(user, "PROFESSOR") || !!user.canDownloadDataset}
                      editable={offers.grants}
                      label={`Allow ${user.email} to download the entire dataset`}
                      onChange={(value) => updateGrant(user, "canDownloadDataset", value)}
                    />
                    <AccountStatusCell user={user} />
                    <td className="px-4 py-3 text-right">
                      {offers.standing !== "MANAGED" ? (
                        // WHY there are no controls, in the reader's own terms: their own account,
                        // one above them, or one at their tier. One label for all three used to
                        // tell a ministry admin that every Admin was their peer.
                        <span className="text-xs text-ink-500">{STANDING_LABEL[offers.standing]}</span>
                      ) : offers.editDetails || offers.remove ? (
                        <RowActions>
                          {offers.editDetails ? (
                            <button
                              type="button"
                              className={rowAction("edit")}
                              onClick={() => setDetails({ open: true, user })}
                            >
                              Edit
                            </button>
                          ) : null}
                          {/* Keyed on HAVING A PASSWORD (`passwordSetAt`), no longer on
                              `authProvider`: Google sign-in used to flip a password account to
                              GOOGLE while it kept its hash, and the link then vanished from exactly
                              the account that needed it. A Google-only account gets no link — giving
                              it a password is a decision, taken through "Set temporary password". */}
                          {offers.passwordLink ? (
                            <button
                              type="button"
                              className={rowAction("neutral")}
                              disabled={linkBusy}
                              onClick={() => issueLink(user)}
                            >
                              Password link
                            </button>
                          ) : null}
                          {offers.passwordLink && mailAvailable ? (
                            <button
                              type="button"
                              className={rowAction("neutral")}
                              disabled={linkBusy}
                              onClick={() => issueLink(user, "EMAIL")}
                            >
                              E-mail a password link
                            </button>
                          ) : null}
                          {offers.requirePasswordChange ? (
                            <button
                              type="button"
                              className={rowAction("neutral")}
                              onClick={() => setRequiring({ open: true, user })}
                            >
                              Require a new password
                            </button>
                          ) : null}
                          {offers.temporaryPassword ? (
                            <button
                              type="button"
                              className={rowAction("neutral")}
                              onClick={() => setTemporary({ open: true, user })}
                            >
                              Set temporary password
                            </button>
                          ) : null}
                          {offers.remove ? (
                            <button type="button" className={rowAction("danger")} onClick={() => remove(user)}>
                              Delete
                            </button>
                          ) : null}
                        </RowActions>
                      ) : (
                        <span className="text-xs text-ink-500">Role only</span>
                      )}
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
      {/* What two of the status words do NOT say, said where they are read (rule 10: a label that
          quietly overstates is the same defect as a list that quietly stops). The first used to say
          "a password somebody else typed still opens the account", which is false of an owner-chosen
          password an administrator has required a change of — and invited a needless temporary
          password, which WOULD have made a shared secret. */}
      <p className="mt-2 text-xs text-ink-muted">
        “{ACCOUNT_STATUS_LABEL.MUST_CHOOSE_NEW_PASSWORD}” means the account must replace its password at its next
        sign-in — either because a provisioner typed it (a temporary password somebody else knows) or because a
        change was required of the owner&apos;s own. Sign-ins have been recorded since August 2026, so an account last
        used before then also reads “{ACCOUNT_STATUS_LABEL.NEVER_SIGNED_IN}”.
      </p>
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
      {/* Closing leaves the account in place (`open` only), so each exit animation plays over the
          real content; see ./AccountDialogs. */}
      <TemporaryPasswordDialog
        open={temporary.open}
        target={temporary.user}
        onClose={() => setTemporary((current) => ({ ...current, open: false }))}
        onSaved={(updated, mustChange) => {
          const who = updated.name || updated.email;
          setTemporary((current) => ({ ...current, open: false }));
          setError(null);
          setNotice(
            mustChange
              ? `Temporary password set for ${who}, who has been signed out everywhere. Send it to them yourself; at their next sign-in they choose their own.`
              : `Password set for ${who}, who has been signed out everywhere. Send it to them yourself; it stays theirs until they change it.`
          );
          load();
        }}
      />
      <RequirePasswordChangeDialog
        open={requiring.open}
        target={requiring.user}
        onClose={() => setRequiring((current) => ({ ...current, open: false }))}
        onSaved={(updated, withLink) => {
          const who = updated.name || updated.email;
          setRequiring((current) => ({ ...current, open: false }));
          setError(null);
          setNotice(`${who} must choose a new password at their next sign-in, and has been signed out everywhere.`);
          load();
          // AFTER the flag: the decision stands whether or not the link is then issued, and a refused
          // link reaches the alert at the top of the page with the server's own sentence.
          if (withLink) void issueLink(updated);
        }}
      />
      <AccountDetailsDialog
        open={details.open}
        target={details.user}
        onClose={() => setDetails((current) => ({ ...current, open: false }))}
        onSaved={(updated) => {
          setDetails((current) => ({ ...current, open: false }));
          setError(null);
          setNotice(`Saved: ${updated.name} · ${updated.email}.`);
          load();
        }}
      />
    </>
  );
}

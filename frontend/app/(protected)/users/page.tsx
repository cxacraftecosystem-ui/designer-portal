"use client";

import { Suspense, useEffect, useRef, useState } from "react";
import Link from "next/link";
import { useSearchParams } from "next/navigation";
import { ShieldCheck, UserPlus } from "lucide-react";

import { AadhaarUserBadge } from "@/components/aadhaar/AadhaarUserBadge";
import { deleteConfirm, useConfirm } from "@/components/dialogs/ConfirmDialog";
import { FieldDialog } from "@/components/dialogs/FieldDialog";
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
import { OpenableRow } from "@/components/data/OpenableRow";
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
  existingAccountId,
  issuePasswordLink,
  revokePasswordLink,
  signOutEverywhere,
  type CreatedAccountLink,
  type IssuedPasswordLink,
  type PasswordLinkDelivery,
  type PasswordMode
} from "@/lib/signIn";
import { resetTwoFactorForUser } from "@/lib/twoFactor";
import type { PageResult, User, UserRole } from "@/lib/types";
import { Toggle, ToggleSwitch } from "@/components/ui/Toggle";
import { SearchableSelect } from "@/components/ui/SearchableSelect";

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
  lastSignIn,
  newAccountBody,
  passwordFrom,
  passwordHasEdgeSpace,
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
import { ActionGroup } from "@/components/ui/ActionGroup";

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
      <span className="inline-flex items-center gap-2">
        <ToggleSwitch checked={included} disabled={!editable} label={label} onChange={onChange} />
        <span aria-hidden>{included ? "Yes" : "No"}</span>
      </span>
    </td>
  );
}

const STATUS_TONE: Record<AccountStatusKind, string> = {
  MUST_CHOOSE_NEW_PASSWORD: "border-amber-500/30 bg-amber-100 text-amber-800",
  GOOGLE_ONLY: "border-line-200 bg-surface-50 text-ink-700",
  NO_PASSWORD: "border-line-200 bg-surface-50 text-ink-700",
  NEVER_SIGNED_IN: "border-line-200 bg-surface-50 text-ink-700",
  ACTIVE: "border-success-600/25 bg-success-100 text-success-600"
};

function AccountStatusCell({ user }: { user: User }) {
  const kind = accountStatus(user);
  const signedIn = lastSignIn(user);
  return (
    <td className="px-4 py-3">
      <span className="inline-flex flex-col gap-0.5">
        <span className={`inline-flex w-fit rounded-full border px-2.5 py-1 text-xs font-medium ${STATUS_TONE[kind]}`}>
          {ACCOUNT_STATUS_LABEL[kind]}
        </span>
        {signedIn ? (
          <span className="text-xs text-ink-500">
            {signedIn.label} {formatDate(signedIn.at)}
          </span>
        ) : null}
        {user.twoFactorEnabled ? (
          <span className="inline-flex w-fit items-center gap-1 rounded-full border border-purple-200 bg-purple-50 px-2.5 py-0.5 text-xs font-medium text-purple-800">
            <ShieldCheck className="h-3 w-3" aria-hidden />
            Two-step on
          </span>
        ) : null}
      </span>
    </td>
  );
}

type CreatedPanel = {
  account: User;
  mustChangePassword: boolean;
  signInAt: string;
  passwordMode: PasswordMode;
  temporaryPassword: string | null;
  passwordLink: CreatedAccountLink | null;
  passwordLinkError: string | null;
};

const SIGN_IN_CHOICES: { mode: PasswordMode; label: string }[] = [
  { mode: "SET", label: "Set a password now" },
  { mode: "GENERATE", label: "Make a temporary password for me" },
  { mode: "NONE", label: "Google, Microsoft or Yahoo only" }
];

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
  const [issuedLink, setIssuedLink] = useState<{ user: User; link: IssuedPasswordLink } | null>(null);
  const [linkBusy, setLinkBusy] = useState(false);
  const [linkCopied, setLinkCopied] = useState(false);
  const [mailAvailable, setMailAvailable] = useState(false);
  const { user: currentUser } = useAuth();
  const { adminMode } = useAdminView();
  const chrome = adminChromeVisible(currentUser, adminMode);
  const provision = canProvisionAccounts(currentUser) && chrome;
  const admin = isAdmin(currentUser) && chrome;
  const masterControls = isMasterAdmin(currentUser) && chrome;
  const offeredRoles = provisionableRoles(currentUser);
  const searchParams = useSearchParams();
  const [prefill, setPrefill] = useState<CreateFormPrefill>(() =>
    createFormPrefill(new URLSearchParams(searchParams.toString()), offeredRoles)
  );
  const [role, setRole] = useState<UserRole | "">(() => createFormRole(prefill, offeredRoles));
  const [formGeneration, setFormGeneration] = useState(0);
  const [requireChange, setRequireChange] = useState(true);
  const [edgeSpace, setEdgeSpace] = useState(false);
  const [creating, setCreating] = useState(false);
  const [createError, setCreateError] = useState<string | null>(null);
  const [passwordMode, setPasswordMode] = useState<PasswordMode>("SET");
  const [sendLink, setSendLink] = useState(false);
  const [existing, setExisting] = useState<{ id: string; email: string } | null>(null);
  const [focusId, setFocusId] = useState<string | null>(null);
  const [temporaryCopied, setTemporaryCopied] = useState(false);
  const [created, setCreated] = useState<CreatedPanel | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [temporary, setTemporary] = useState<{ open: boolean; user: User | null }>({ open: false, user: null });
  const [requiring, setRequiring] = useState<{ open: boolean; user: User | null }>({ open: false, user: null });
  const [details, setDetails] = useState<{ open: boolean; user: User | null }>({ open: false, user: null });
  const [viewing, setViewing] = useState<User | null>(null);
  const [data, setData] = useState<PageResult<User> | null>(null);
  const [query, setQuery] = useState("");
  const [applied, setApplied] = useState("");
  const [page, setPage] = useState(1);
  const [error, setError] = useState<string | null>(null);
  const skipFirstDebounce = useRef(true);
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
      const body: Record<string, unknown> = {};
      if (grantAdmin) body.role = "ADMIN";
      if (grantQuestionnaire) body.canManageQuestionnaire = true;
      if (grantDataset) body.canDownloadDataset = true;
      if (Object.keys(body).length === 0) {
        setAdminMessage("Pick at least one capability.");
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

  async function loadAllUsers() {
    if (!masterControls) return;
    try {
      const result = await listResource<User>("/users", { pageSize: LIST_PAGE_CEILING });
      setAllUsers(result.items);
    } catch {
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

  useEffect(() => {
    if (hasPrefill(prefill)) window.history.replaceState(null, "", "/users");
  }, [prefill]);

  async function submit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = new FormData(event.currentTarget);
    if (creating) return;
    if (!role) {
      setCreateError("Choose the role for this account.");
      return;
    }
    const body = newAccountBody(
      {
        name: requiredText(form, "name"),
        email: requiredText(form, "email"),
        role,
        passwordMode,
        password: passwordFrom(form),
        mustChangePassword: requireChange,
        sendPasswordLink: passwordMode === "NONE" && mailAvailable && sendLink,
        canManageQuestionnaire: form.get("canManageQuestionnaire") === "on",
        canDownloadDataset: form.get("canDownloadDataset") === "on"
      },
      { admin }
    );
    setCreating(true);
    setCreateError(null);
    setExisting(null);
    try {
      const account = await createPasswordAccount(body);
      setCreated({
        account,
        mustChangePassword: account.mustChangePassword ?? body.mustChangePassword ?? false,
        signInAt: `${window.location.origin}/login`,
        passwordMode,
        temporaryPassword: account.temporaryPassword ?? null,
        passwordLink: account.passwordLink ?? null,
        passwordLinkError: account.passwordLinkError ?? null
      });
      setTemporaryCopied(false);
      setIssuedLink(null);
      setNotice(null);
      setPrefill(NO_PREFILL);
      setRole(createFormRole(NO_PREFILL, offeredRoles));
      setFormGeneration((value) => value + 1);
      setRequireChange(true);
      setPasswordMode("SET");
      setSendLink(false);
      setEdgeSpace(false);
      setShowNewPassword(false);
      load();
    } catch (err) {
      setCreateError(err instanceof Error ? err.message : "Unable to create the account");
      const id = existingAccountId(err);
      setExisting(id ? { id, email: body.email } : null);
    } finally {
      setCreating(false);
    }
  }

  function openExisting() {
    if (!existing) return;
    setQuery(existing.email);
    setApplied(existing.email);
    setPage(1);
    setFocusId(existing.id);
    setExisting(null);
    setCreateError(null);
    requestAnimationFrame(() => document.getElementById("users-table")?.scrollIntoView({ block: "start" }));
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

  async function turnOffTwoFactor(user: User) {
    const ok = await confirm({
      title: "Turn off two-step verification?",
      body: (
        <>
          <span className="font-medium text-ink-900">{user.name || user.email}</span> will sign in with just their
          password or their usual sign-in, and is signed out everywhere now. Their recovery codes stop working.
        </>
      ),
      note: "They can turn it on again from their own Settings, with a new phone.",
      confirmLabel: "Turn off",
      tone: "warning"
    });
    if (!ok) return;
    try {
      await resetTwoFactorForUser(user.id);
      setError(null);
      load();
    } catch (err) {
      setError(err instanceof Error ? err.message : "Unable to turn off two-step verification");
    }
  }

  async function endEverySession(user: User) {
    const ok = await confirm({
      title: "Sign out of every device?",
      body: (
        <>
          <span className="font-medium text-ink-900">{user.name || user.email}</span> is signed out on every phone,
          tablet and browser now, and signs in again the usual way.
        </>
      ),
      note: "Their password and two-step verification stay as they are.",
      confirmLabel: "Sign out everywhere",
      tone: "warning"
    });
    if (!ok) return;
    try {
      await signOutEverywhere(user.id);
      setError(null);
      load();
    } catch (err) {
      setError(err instanceof Error ? err.message : "Unable to sign them out");
    }
  }

  async function remove(user: User) {
    const ok = await confirm(
      deleteConfirm(
        "Delete this account?",
        <>
          <span className="font-medium text-ink-900">{user.name || user.email}</span> loses access immediately. This can&apos;t be undone.
        </>,
        "Their records are kept."
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

  const createdLinkOffered = created ? createdPanelOffersLink(currentUser, created.account, { provision, admin }) : false;
  const createdLinkNote = created ? createdPanelLinkNote(currentUser, created.account, { provision, admin }) : null;

  return (
    <>
      <PageHeader
        title="Users"
        description="Accounts, roles and access."
        icon={<ShieldCheck className="h-5 w-5" aria-hidden />}
      />
      {error ? (
        <div role="alert" className="mb-4 rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">
          {error}
        </div>
      ) : null}
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
          <ActionGroup>
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
          </ActionGroup>
        </div>
      ) : issuedLink ? (
        <div className="mb-4 grid gap-2 rounded-md border border-line-200 bg-field-50 px-3 py-3">
          <p className="text-sm font-medium text-ink-900">
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
            <ActionGroup className="max-sm:w-full"><button
              type="button"
              className="field-button-secondary"
              onClick={async () => {
                try {
                  await navigator.clipboard.writeText(issuedLink.link.link ?? "");
                  setLinkCopied(true);
                } catch {
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
            </button></ActionGroup>
          </div>
          <p className="text-xs leading-5 text-ink-500">
            Shown once. Send it yourself — it works once and expires{" "}
            {new Date(issuedLink.link.expiresAt).toLocaleString()}.
          </p>
        </div>
      ) : null}
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
            {created.passwordMode === "NONE" ? (
              <>
                <p className="text-sm leading-6 text-ink-700">
                  They sign in with Google, Microsoft or Yahoo using{" "}
                  <span className="break-all font-medium text-ink-900">{created.account.email}</span>, at{" "}
                  <span className="break-all font-medium text-ink-900">{created.signInAt}</span>.
                </p>
                {created.passwordLink ? (
                  <p className="text-sm leading-6 text-ink-700">
                    A password link was also emailed; it expires{" "}
                    {new Date(created.passwordLink.expiresAt).toLocaleString()}.
                  </p>
                ) : null}
                {created.passwordLinkError ? <p className={CAUTION_CLASS}>{created.passwordLinkError}</p> : null}
              </>
            ) : created.temporaryPassword ? (
              <>
                <p className="text-sm leading-6 text-ink-700">
                  Temporary password below, shown once. Send it separately from their address,{" "}
                  <span className="break-all font-medium text-ink-900">{created.account.email}</span>. They sign in at{" "}
                  <span className="break-all font-medium text-ink-900">{created.signInAt}</span>.
                </p>
                <div className="flex flex-wrap items-center gap-2">
                  <input
                    readOnly
                    value={created.temporaryPassword}
                    aria-label="Temporary password"
                    onFocus={(event) => event.currentTarget.select()}
                    className="field-input min-w-0 flex-1 font-mono text-sm"
                  />
                  <button
                    type="button"
                    className="field-button-secondary"
                    onClick={async () => {
                      try {
                        await navigator.clipboard.writeText(created.temporaryPassword ?? "");
                        setTemporaryCopied(true);
                      } catch {
                        setTemporaryCopied(false);
                      }
                    }}
                  >
                    {temporaryCopied ? "Copied" : "Copy"}
                  </button>
                </div>
                <p className="text-sm leading-6 text-ink-700">
                  They must change it when they first sign in, and this one then stops working.
                </p>
              </>
            ) : (
              <>
                <p className="text-sm leading-6 text-ink-700">
                  Hand them two things, and not in the same message: the address they sign in with,{" "}
                  <span className="break-all font-medium text-ink-900">{created.account.email}</span>, and the password
                  you typed — this page does not show it again. They sign in at{" "}
                  <span className="break-all font-medium text-ink-900">{created.signInAt}</span>.
                </p>
                <p className="text-sm leading-6 text-ink-700">
                  {created.mustChangePassword
                    ? "At their first sign-in they choose their own password before they can do anything else, and the one you typed stops working."
                    : "You did not require a new password, so the one you typed stays theirs until they change it."}
                  {createdLinkOffered ? " Rather not pass a password on? A password link lets them choose their own instead." : null}
                </p>
              </>
            )}
            {createdLinkNote ? <p className="text-sm leading-6 text-ink-700">{createdLinkNote}</p> : null}
            <ActionGroup>
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
              <button
                type="button"
                className="field-button-secondary"
                onClick={() => {
                  setCreated(null);
                  setTemporaryCopied(false);
                }}
              >
                Done
              </button>
            </ActionGroup>
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
              Add a person
            </h2>
            <p className="mt-1 text-sm leading-6 text-ink-muted">
              Name, address, role and sign-in.{" "}
              {admin
                ? "Also adds the address to “Who may sign in”."
                : "Also lets the address sign in, unless an admin refused it."}
            </p>
          </div>
          <div className="grid gap-3 md:grid-cols-2 lg:grid-cols-4">
            <Field label="Name" required>
              <TextInput name="name" required maxLength={160} autoComplete="off" defaultValue={prefill.name} />
            </Field>
            <Field label="Email" required>
              <TextInput name="email" type="email" required autoComplete="off" defaultValue={prefill.email} />
            </Field>
            {passwordMode === "SET" ? (
            <Field label="Password" required>
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
            ) : null}
            <Field label="Role">
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
            <p className="text-xs leading-5 text-ink-500">Choose a role.</p>
          ) : null}
          {createsPeerAccount(currentUser, role) ? (
            <p className={CAUTION_CLASS}>
              {isMasterAdmin(currentUser)
                ? "Once created, other master admins can't manage its password."
                : "Your own role — once created, only a higher role can manage it."}
            </p>
          ) : null}
          <fieldset className="grid gap-2">
            <legend className="field-label mb-1">How will they sign in?</legend>
            {SIGN_IN_CHOICES.map((choice) => (
              <label key={choice.mode} className="flex items-start gap-2 text-sm text-ink-700">
                <input
                  type="radio"
                  name="passwordMode"
                  value={choice.mode}
                  checked={passwordMode === choice.mode}
                  onChange={() => setPasswordMode(choice.mode)}
                  className="mt-1"
                />
                <span>{choice.label}</span>
              </label>
            ))}
            {passwordMode === "GENERATE" ? (
              <p className="text-xs leading-5 text-ink-500">
                A strong password is made for you and shown once, after the account is saved. They must change it when
                they first sign in.
              </p>
            ) : null}
            {passwordMode === "NONE" && mailAvailable ? (
              <Toggle checked={sendLink} onChange={setSendLink} label="Email them a link to set a password too" />
            ) : null}
          </fieldset>
          {passwordMode === "SET" && edgeSpace ? <EdgeSpaceNote /> : null}
          {passwordMode === "SET" ? (
          <div className="grid gap-2">
            <Toggle checked={requireChange} onChange={setRequireChange} label="Require a new password at first sign-in" />
            <RequireChangeNote required={requireChange} when="first" />
          </div>
          ) : null}
          {admin ? (
            <div className="grid gap-1 sm:grid-cols-2 sm:gap-4">
              <Toggle name="canManageQuestionnaire" label="Manage questionnaire" />
              <Toggle name="canDownloadDataset" label="Download dataset" />
            </div>
          ) : null}
          {createError ? (
            <div role="alert" className="rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">
              {createError}
              {existing ? (
                <div className="mt-2">
                  <button type="button" className="field-button-secondary" onClick={openExisting}>
                    Open their account
                  </button>
                </div>
              ) : null}
            </div>
          ) : null}
          <div>
            <button className="field-button" disabled={creating}>
              <UserPlus className="h-4 w-4" aria-hidden />
              {creating ? "Adding…" : "Add person"}
            </button>
          </div>
        </form>
      ) : null}
      <p className="mb-4 text-xs text-ink-muted">
        {ROLES_BY_RANK.length} roles: {ROLES_BY_RANK.map(roleLabel).join(" → ")}. You can promote up to your own role and manage roles below it.
      </p>
      <p className="mb-4 text-xs text-ink-muted">
        Designers are added to the roster automatically, unless suspended there.{" "}
        {canManageDesignerRoster(currentUser) ? (
          <Link href="/admin/designers" className="font-medium text-purple-700 dark:text-purple-300 underline-offset-2 hover:underline">
            Open the designer roster
          </Link>
        ) : (
          <span>Ask an admin if a designer can&apos;t sign in.</span>
        )}
      </p>
      <p className="mb-4 text-xs text-ink-muted">
        Editing crafts and workshops needs Professor; creating records needs Researcher.
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
          <div id="users-table" className="overflow-x-auto scroll-mt-28">
            <table className="w-full min-w-[1000px] text-left text-sm">
              <thead className="bg-surface-50 text-xs uppercase text-ink-500">
                <tr>
                  <ResizableTh>Name</ResizableTh>
                  <ResizableTh>Email</ResizableTh>
                  <ResizableTh>Role</ResizableTh>
                  <ResizableTh>Questionnaire</ResizableTh>
                  <ResizableTh>Dataset</ResizableTh>
                  <ResizableTh>Sign-in</ResizableTh>
                  <ResizableTh className="text-right">Actions</ResizableTh>
                </tr>
              </thead>
              <tbody className="divide-y divide-line-200">
                {data.items.map((user) => {
                  const offers = rowOffers(currentUser, user, { provision, admin });
                  return (
                  <OpenableRow
                    key={user.id}
                    onOpen={() => setViewing(user)}
                    data-flash={focusId === user.id ? "true" : undefined}
                    className="fr-flash-row"
                  >
                    <td className="px-4 py-3 font-medium text-ink-900">
                      <button
                        type="button"
                        onClick={() => setViewing(user)}
                        className="rounded-sm text-left font-medium text-ink-900 underline-offset-2 hover:underline"
                      >
                        {user.name}
                      </button>
                      <AadhaarUserBadge account={user} viewer={currentUser} onDecided={() => void load()} />
                    </td>
                    <td className="px-4 py-3 text-ink-700">{user.email}</td>
                    <td className="px-4 py-3">
                      {offers.changeRole ? (
                        <SearchableSelect
                          className="max-w-52"
                          value={user.role}
                          ariaLabel={`Role of ${user.email}`}
                          searchable={false}
                          onChange={(value) => updateRole(user, value)}
                          options={assignableRoles(currentUser)
                            .filter((role) => role !== "MASTER_ADMIN" || isMasterAdmin(currentUser))
                            .map((role) => ({ value: role, label: roleLabel(role) }))}
                          advanceOnSelect={false}
                        />
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
                    <GrantCell
                      included={hasRank(user, "PROFESSOR") || !!user.canDownloadDataset}
                      editable={offers.grants}
                      label={`Allow ${user.email} to download the entire dataset`}
                      onChange={(value) => updateGrant(user, "canDownloadDataset", value)}
                    />
                    <AccountStatusCell user={user} />
                    <td className="px-4 py-3 text-right">
                      {offers.standing !== "MANAGED" ? (
                        <span className="text-xs text-ink-500">{STANDING_LABEL[offers.standing]}</span>
                      ) : offers.editDetails || offers.remove ? (
                        <RowActions>
                          {offers.editDetails ? (
                            <button
                              type="button"
                              className={rowAction("edit")}
                              onClick={() => setDetails({ open: true, user })}
                            >
                              Update
                            </button>
                          ) : null}
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
                          {offers.resetTwoFactor ? (
                            <button
                              type="button"
                              className={rowAction("neutral")}
                              onClick={() => turnOffTwoFactor(user)}
                            >
                              Turn off two-step verification
                            </button>
                          ) : null}
                          {offers.signOutEverywhere ? (
                            <button
                              type="button"
                              className={rowAction("neutral")}
                              onClick={() => endEverySession(user)}
                            >
                              Sign out of every device
                            </button>
                          ) : null}
                          {offers.remove ? (
                            <button type="button" data-danger className={rowAction("danger")} onClick={() => remove(user)}>
                              Delete
                            </button>
                          ) : null}
                        </RowActions>
                      ) : (
                        <span className="text-xs text-ink-500">Role only</span>
                      )}
                    </td>
                  </OpenableRow>
                  );
                })}
              </tbody>
            </table>
          </div>
        )}
        {data ? <Pagination page={data.page} pages={data.pages} total={data.total} onPage={setPage} /> : null}
      </section>
      <p className="mt-2 text-xs text-ink-muted">
        “{ACCOUNT_STATUS_LABEL.MUST_CHOOSE_NEW_PASSWORD}”: a new password is required at next sign-in — because a provisioner typed it, or because a change was
        required of the owner&apos;s own. “{ACCOUNT_STATUS_LABEL.NEVER_SIGNED_IN}”: no sign-in yet.
      </p>
      {masterControls ? (
        <section className="panel mt-6 p-4">
          <h2 className="font-display font-bold text-xl text-ink">Admin management</h2>
          <p className="mt-1 text-sm text-ink-muted">
            Grant capabilities to selected users. Revoke them in the table above.
          </p>
          {adminMessage ? <div className="mt-3 rounded-md border border-emerald-200 bg-emerald-50 px-3 py-2 text-sm text-emerald-700">{adminMessage}</div> : null}
          <div className="mt-4 grid grid-cols-[minmax(0,1fr)] gap-4 lg:grid-cols-[minmax(0,1.4fr)_minmax(0,1fr)]">
            <div className="min-w-0 rounded-md border border-line-200 bg-field-50 p-3">
              <div className="mb-2 text-xs font-semibold uppercase tracking-wide text-ink-soft">Select users ({adminSelection.size} selected)</div>
              <div className="grid max-h-72 grid-cols-[minmax(0,1fr)] gap-1 overflow-y-auto">
                {allUsers
                  .filter((user) => user.role !== "MASTER_ADMIN")
                  .map((user) => (
                    <label key={user.id} className="flex min-w-0 items-center gap-2 rounded px-2 py-1 hover:bg-field-100">
                      <input type="checkbox" checked={adminSelection.has(user.id)} onChange={() => toggleAdminSelection(user.id)} />
                      <span className="min-w-0 flex-1 truncate text-sm text-ink">
                        {user.name} <span className="text-ink-muted">· {user.email}</span>
                      </span>
                      <span className="shrink-0 rounded-full bg-field-200 px-2 py-0.5 text-xs text-ink-muted">{roleLabel(user.role)}</span>
                    </label>
                  ))}
                {allUsers.filter((user) => user.role !== "MASTER_ADMIN").length === 0 ? (
                  <p className="px-2 py-1 text-sm text-ink-muted">No eligible users.</p>
                ) : null}
              </div>
            </div>
            <div className="grid min-w-0 content-start gap-2 rounded-md border border-line-200 bg-field-50 p-3">
              <div className="mb-1 text-xs font-semibold uppercase tracking-wide text-ink-soft">Grant</div>
              <Toggle checked={grantAdmin} onChange={setGrantAdmin} label="Administrator access (Admin role)" />
              <Toggle checked={grantQuestionnaire} onChange={setGrantQuestionnaire} label="Manage questionnaire" />
              <Toggle checked={grantDataset} onChange={setGrantDataset} label="Download dataset" />
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
              ? `Temporary password set for ${who}; they're signed out. Send it yourself.`
              : `Password set for ${who}; they're signed out. Send it yourself.`
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
          setNotice(`${who} is signed out and must choose a new password.`);
          load();
          if (withLink) void issueLink(updated);
        }}
      />
      <FieldDialog
        open={viewing !== null}
        onClose={() => setViewing(null)}
        title={viewing?.name ?? "Account"}
        description={viewing?.email}
        footer={
          viewing ? (
            <ActionGroup align="end">
              <button type="button" className="field-button-secondary" onClick={() => setViewing(null)}>
                Close
              </button>
              {rowOffers(currentUser, viewing, { provision, admin }).editDetails ? (
                <button
                  type="button"
                  className="field-button"
                  onClick={() => {
                    const target = viewing;
                    setViewing(null);
                    setDetails({ open: true, user: target });
                  }}
                >
                  Update
                </button>
              ) : null}
            </ActionGroup>
          ) : null
        }
      >
        {viewing ? (
          <dl className="mt-4 grid gap-2 text-sm">
            {[
              ["Role", roleLabel(viewing.role)],
              ["May manage the questionnaire", hasRank(viewing, "PROFESSOR") || viewing.canManageQuestionnaire ? "Yes" : "No"],
              ["May download the dataset", hasRank(viewing, "PROFESSOR") || viewing.canDownloadDataset ? "Yes" : "No"],
              ["Sign-in", ACCOUNT_STATUS_LABEL[accountStatus(viewing)]],
              ["First signed in", viewing.firstLoginAt ? formatDate(viewing.firstLoginAt) : "Not yet"],
              ["Last signed in", viewing.lastLoginAt ? formatDate(viewing.lastLoginAt) : "Not recorded"],
              ["Two-step verification", viewing.twoFactorEnabled ? "Turned on" : "Not turned on"]
            ].map(([label, value]) => (
              <div key={label} className="grid gap-0.5 sm:grid-cols-[13rem_minmax(0,1fr)] sm:gap-3">
                <dt className="font-semibold text-ink-900">{label}</dt>
                <dd className="text-ink-700">{value}</dd>
              </div>
            ))}
          </dl>
        ) : null}
      </FieldDialog>
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

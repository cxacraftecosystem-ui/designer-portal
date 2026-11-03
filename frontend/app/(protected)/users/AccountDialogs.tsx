"use client";

import { useId, useRef, useState } from "react";
import { AlertTriangle, KeyRound, UserPen } from "lucide-react";

import { FieldDialog } from "@/components/dialogs/FieldDialog";
import { PasswordRevealButton } from "@/components/ui/PasswordReveal";
import { requiredText } from "@/lib/forms";
import {
  MAX_PASSWORD_LENGTH,
  MIN_PASSWORD_LENGTH,
  correctAccountDetails,
  passwordRuleLine,
  requirePasswordChange,
  setTemporaryPassword
} from "@/lib/signIn";
import type { User } from "@/lib/types";
import { Toggle } from "@/components/ui/Toggle";

import { hasPassword, passwordFrom, passwordHasEdgeSpace } from "./accountAdmin";

export const CAUTION_CLASS =
  "rounded-md border border-amber-500/30 bg-amber-100 px-3 py-2 text-xs leading-5 text-amber-800";

const ERROR_CLASS =
  "rounded-md border border-error-600/30 bg-error-100 px-3 py-2 text-sm leading-6 text-error-600";

export function RequireChangeNote({ required, when }: { required: boolean; when: "first" | "next" }) {
  return (
    <div aria-live="polite">
      {required ? (
        <p className="text-xs leading-5 text-ink-500">
          They choose their own at their {when} sign-in.
        </p>
      ) : (
        <p className={CAUTION_CLASS}>
          Not required: the password you type stays theirs until they change it themselves, so you — and anyone who
          sees how you send it — will keep knowing it.
        </p>
      )}
    </div>
  );
}

export function EdgeSpaceNote() {
  return (
    <p className={CAUTION_CLASS}>
      Starts or ends with a space — they must type it too.
    </p>
  );
}

export function TemporaryPasswordDialog({
  open,
  target,
  onClose,
  onSaved
}: {
  open: boolean;
  target: User | null;
  onClose: () => void;
  onSaved: (updated: User, mustChangePassword: boolean) => void;
}) {
  const formId = useId();
  const passwordId = useId();
  const passwordRef = useRef<HTMLInputElement | null>(null);
  const [revealed, setRevealed] = useState(false);
  const [requireChange, setRequireChange] = useState(true);
  const [edgeSpace, setEdgeSpace] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  function reset() {
    setRevealed(false);
    setRequireChange(true);
    setEdgeSpace(false);
    setError(null);
  }

  function close() {
    if (busy) return;
    reset();
    onClose();
  }

  async function submit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const password = passwordFrom(new FormData(event.currentTarget));
    if (!open || !target || busy) return;
    setBusy(true);
    setError(null);
    try {
      const updated = await setTemporaryPassword(target.id, password, requireChange);
      const required = updated.mustChangePassword ?? requireChange;
      reset();
      onSaved(updated, required);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Unable to set the password");
    } finally {
      setBusy(false);
    }
  }

  const who = target ? target.name || target.email : "";

  return (
    <FieldDialog
      open={open && target !== null}
      onClose={close}
      busy={busy}
      role="alertdialog"
      tone="warning"
      icon={<KeyRound className="h-4 w-4" aria-hidden />}
      title="Set a temporary password"
      description={target ? `${target.name} · ${target.email}` : undefined}
      initialFocusRef={passwordRef}
      footer={
        <>
          <button type="button" className="field-button-secondary" disabled={busy} onClick={close}>
            Cancel
          </button>
          <button type="submit" form={formId} className="field-button" disabled={busy}>
            {busy ? "Saving…" : "Set password"}
          </button>
        </>
      }
    >
      {target ? (
        <form key={target.id} id={formId} onSubmit={submit} className="grid gap-3">
          {error ? (
            <p role="alert" className={ERROR_CLASS}>
              {error}
            </p>
          ) : null}
          <p className="text-sm leading-6 text-ink-700">
            <strong className="font-medium text-ink-900">Saving signs {who} out everywhere.</strong>
          </p>
          {hasPassword(target) ? null : (
            <p className="text-sm leading-6 text-ink-700">
              This account has no password today{target.authProvider === "GOOGLE" ? " — it signs in with Google" : ""}.
              Setting one lets it sign in with this password as well.
            </p>
          )}
          <div className="grid gap-1">
            <label htmlFor={passwordId} className="field-label">
              Temporary password
            </label>
            <div className="relative">
              <input
                id={passwordId}
                ref={passwordRef}
                name="password"
                type={revealed ? "text" : "password"}
                required
                minLength={MIN_PASSWORD_LENGTH}
                maxLength={MAX_PASSWORD_LENGTH}
                autoComplete="new-password"
                autoCapitalize="none"
                autoCorrect="off"
                spellCheck={false}
                onChange={(event) => setEdgeSpace(passwordHasEdgeSpace(event.target.value))}
                className="field-input pr-11"
              />
              <PasswordRevealButton revealed={revealed} onToggle={() => setRevealed((value) => !value)} size={18} />
            </div>
            <p className="text-xs leading-5 text-ink-500">
              {passwordRuleLine("Send it yourself; it isn't shown again.")}
            </p>
          </div>
          {edgeSpace ? <EdgeSpaceNote /> : null}
          <Toggle checked={requireChange} onChange={setRequireChange} label="Require a new password at next sign-in" />
          <RequireChangeNote required={requireChange} when="next" />
        </form>
      ) : null}
    </FieldDialog>
  );
}

export function RequirePasswordChangeDialog({
  open,
  target,
  onClose,
  onSaved
}: {
  open: boolean;
  target: User | null;
  onClose: () => void;
  onSaved: (updated: User, withLink: boolean) => void;
}) {
  const confirmRef = useRef<HTMLButtonElement | null>(null);
  const [withLinkChoice, setWithLinkChoice] = useState<boolean | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const withLink = withLinkChoice ?? target?.authProvider === "GOOGLE";

  function reset() {
    setWithLinkChoice(null);
    setError(null);
  }

  function close() {
    if (busy) return;
    reset();
    onClose();
  }

  async function confirm() {
    if (!open || !target || busy) return;
    setBusy(true);
    setError(null);
    try {
      const updated = await requirePasswordChange(target.id);
      const asked = withLink;
      reset();
      onSaved(updated, asked);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Unable to require a new password");
    } finally {
      setBusy(false);
    }
  }

  const who = target ? target.name || target.email : "";

  return (
    <FieldDialog
      open={open && target !== null}
      onClose={close}
      busy={busy}
      role="alertdialog"
      tone="warning"
      icon={<AlertTriangle className="h-4 w-4" aria-hidden />}
      title="Require a new password at next sign-in?"
      description={target ? `${target.name} · ${target.email}` : undefined}
      initialFocusRef={confirmRef}
      footer={
        <>
          <button type="button" className="field-button-secondary" disabled={busy} onClick={close}>
            Cancel
          </button>
          <button ref={confirmRef} type="button" className="field-button" disabled={busy} onClick={confirm}>
            {busy ? "Working…" : "Require a new password"}
          </button>
        </>
      }
    >
      {target ? (
        <div key={target.id} className="grid gap-3">
          {error ? (
            <p role="alert" className={ERROR_CLASS}>
              {error}
            </p>
          ) : null}
          <p className="text-sm leading-6 text-ink-700">
            <strong className="font-medium text-ink-900">{who} is signed out everywhere now</strong>, and at their next
            sign-in must choose a new password before they can do anything else.
          </p>
          <p className="text-sm leading-6 text-ink-700">
            Their current password works once, to set a new one. Somebody who signs in with Google is asked for it too;
            if they don&apos;t know it, they need a password link.
          </p>
          <Toggle
            checked={withLink}
            disabled={busy}
            onChange={setWithLinkChoice}
            label="Also issue a password link (shown once)"
          />
        </div>
      ) : null}
    </FieldDialog>
  );
}

export function AccountDetailsDialog({
  open,
  target,
  onClose,
  onSaved
}: {
  open: boolean;
  target: User | null;
  onClose: () => void;
  onSaved: (updated: User) => void;
}) {
  const formId = useId();
  const nameId = useId();
  const emailId = useId();
  const nameRef = useRef<HTMLInputElement | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  function close() {
    if (busy) return;
    setError(null);
    onClose();
  }

  async function submit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = new FormData(event.currentTarget);
    if (!open || !target || busy) return;
    const name = requiredText(form, "name");
    const email = requiredText(form, "email");
    const changes: { name?: string; email?: string } = {};
    if (name && name !== target.name) changes.name = name;
    if (email && email.toLowerCase() !== target.email.toLowerCase()) changes.email = email;
    if (!changes.name && !changes.email) {
      close();
      return;
    }
    setBusy(true);
    setError(null);
    try {
      const updated = await correctAccountDetails(target.id, changes);
      setError(null);
      onSaved(updated);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Unable to save these details");
    } finally {
      setBusy(false);
    }
  }

  return (
    <FieldDialog
      open={open && target !== null}
      onClose={close}
      busy={busy}
      icon={<UserPen className="h-4 w-4" aria-hidden />}
      title="Correct name or address"
      description={target ? `${target.name} · ${target.email}` : undefined}
      initialFocusRef={nameRef}
      footer={
        <>
          <button type="button" className="field-button-secondary" disabled={busy} onClick={close}>
            Cancel
          </button>
          <button type="submit" form={formId} className="field-button" disabled={busy}>
            {busy ? "Saving…" : "Save"}
          </button>
        </>
      }
    >
      {target ? (
        <form key={target.id} id={formId} onSubmit={submit} className="grid gap-3">
          {error ? (
            <p role="alert" className={ERROR_CLASS}>
              {error}
            </p>
          ) : null}
          <div className="grid gap-1">
            <label htmlFor={nameId} className="field-label">
              Name
            </label>
            <input
              id={nameId}
              ref={nameRef}
              name="name"
              required
              maxLength={160}
              autoComplete="off"
              defaultValue={target.name}
              className="field-input"
            />
          </div>
          <div className="grid gap-1">
            <label htmlFor={emailId} className="field-label">
              Email
            </label>
            <input
              id={emailId}
              name="email"
              type="email"
              required
              autoComplete="off"
              defaultValue={target.email}
              className="field-input"
            />
          </div>
          <p className="text-xs leading-5 text-ink-500">
            They&apos;ll sign in with the new address.
          </p>
        </form>
      ) : null}
    </FieldDialog>
  );
}

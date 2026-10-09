"use client";

/**
 * The three row actions on /users that need more than a yes or a no: setting a temporary password,
 * correcting a name or a sign-in address, and requiring a new password at next sign-in — which was a
 * plain confirm on the page until it had to offer a password link beside the decision (see
 * {@link RequirePasswordChangeDialog}).
 *
 * Both open over an account the reader PROVISIONS (`rowOffers` in ./accountAdmin decides that), and
 * both show the server's own sentence on a refusal — a 403, 409 or 422 here is a rule the server
 * holds that this screen could not see coming, and its wording is the remedy.
 *
 * `open` IS SEPARATE FROM `target` so the page can close a dialog and leave the account in place:
 * the exit animation then plays over the real content rather than an empty card — the same reason
 * `ConfirmProvider` keeps its options after it closes. Each form is keyed on the account, so a
 * dialog reopened for somebody else never starts with the previous person's text.
 */

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

import { hasPassword, passwordFrom, passwordHasEdgeSpace } from "./accountAdmin";

/** The amber box both forms on this page use for "this choice has a cost". Literal on both sides. */
export const CAUTION_CLASS =
  "rounded-md border border-amber-500/30 bg-amber-100 px-3 py-2 text-xs leading-5 text-amber-800";

const ERROR_CLASS =
  "rounded-md border border-error-600/30 bg-error-100 px-3 py-2 text-sm leading-6 text-error-600";

/**
 * The sentence under "Require a new password …" — one wording for the create form and this dialog,
 * so the two places an account provisioner makes the same choice describe it the same way. `when`
 * is "first" on a new account and "next" on an existing one.
 */
export function RequireChangeNote({ required, when }: { required: boolean; when: "first" | "next" }) {
  // A live region that is always mounted: unticking the box is the moment the warning matters, and
  // a region created together with its first sentence announces nothing.
  return (
    <div aria-live="polite">
      {required ? (
        <p className="text-xs leading-5 text-ink-500">
          The password you type works until their {when} sign-in, where they choose their own before they can do
          anything else.
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

/** Shown while the typed password begins or ends with whitespace, which is kept exactly as typed. */
export function EdgeSpaceNote() {
  return (
    <p className={CAUTION_CLASS}>
      This password begins or ends with a space. It is kept exactly as typed, so they must type that space too.
    </p>
  );
}

/**
 * SET A TEMPORARY PASSWORD FOR SOMEBODY ELSE.
 *
 * `alertdialog`, so a stray click on the backdrop cannot throw away a typed password, and amber,
 * because saving signs the person out everywhere — every session on the web and on the phone ends
 * at once (the server stamps `sessionsValidFrom`). The dialog says so before the button is pressed,
 * not after.
 *
 * The require-change box is ON by default and is sent in BOTH directions: the server's default for
 * a password set for somebody else is "must change", so an unticked box that sent nothing would
 * quietly be a ticked one.
 */
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

  /**
   * Everything this dialog holds about ONE account, cleared on every way out. The component stays
   * mounted for the life of the page, so without this the next account would open with the last
   * one's choices — the box unticked for somebody nobody decided to leave it unticked for.
   */
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
    // Read before any await: React nulls `currentTarget` across one.
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
      // The submit button names its form with `form=` because the footer is drawn outside the panel
      // body, and that is what lets the browser's own length checks run before anything is sent.
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
            <strong className="font-medium text-ink-900">Saving signs {who} out everywhere</strong> — every session they
            have open, on the web and on the phone, ends at once.
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
                // `new-password`: this box holds somebody ELSE's password, and a browser that took it
                // for a sign-in form would offer to fill in the administrator's own.
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
              {passwordRuleLine("Send it to them yourself — once saved, nothing shows it again.")}
            </p>
          </div>
          {edgeSpace ? <EdgeSpaceNote /> : null}
          <label className="flex items-start gap-2 text-sm text-ink-700">
            <input
              type="checkbox"
              checked={requireChange}
              onChange={(event) => setRequireChange(event.target.checked)}
              className="mt-1"
            />
            <span>Require a new password at next sign-in</span>
          </label>
          <RequireChangeNote required={requireChange} when="next" />
        </form>
      ) : null}
    </FieldDialog>
  );
}

/**
 * REQUIRE A NEW PASSWORD AT NEXT SIGN-IN, on an account that has one — its password is not touched.
 *
 * A dialog of its own rather than the page's plain confirm, for two things the person on the other
 * end will meet that nothing on this page shows the reader:
 *
 *  1. GOOGLE. The screen they are held at asks for their PRESENT password — change-password requires
 *     it, and the Google door has nothing to fill it with. Somebody who only ever signs in with Google
 *     may never have known the password this account carries (an account made with a temporary
 *     password and later entered through Google; since 2026-10-09 any password account may be), and
 *     every wrong guess is charged to the account's sign-in budget. Their way through is a password
 *     link, so the dialog offers one with the same decision — ticked already for an account the
 *     server still records as a Google sign-in, which is exactly that case.
 *  2. OLD PHONES. A handset on 0.0.15 or older words the same screen "An administrator set your
 *     password", which is false here: nobody set anything. The neutral wording ships with the next
 *     build; until then the reader is told what will be quoted back to them.
 *
 * `alertdialog` and amber like {@link TemporaryPasswordDialog}, because saving signs the person out
 * everywhere at once, and the dialog says so before the button is pressed.
 */
export function RequirePasswordChangeDialog({
  open,
  target,
  onClose,
  onSaved
}: {
  open: boolean;
  target: User | null;
  onClose: () => void;
  /** `withLink`: the reader also asked for a password link, which the page issues and shows. */
  onSaved: (updated: User, withLink: boolean) => void;
}) {
  const confirmRef = useRef<HTMLButtonElement | null>(null);
  /** Null until the reader touches the box: until then it follows the account — see the header, 1. */
  const [withLinkChoice, setWithLinkChoice] = useState<boolean | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const withLink = withLinkChoice ?? target?.authProvider === "GOOGLE";

  /** Everything this dialog holds about ONE account, cleared on every way out — as the dialog above. */
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
      // The decision the reader came to make, as the plain confirm this replaced had it: the amber tone
      // already slows the hand, and Cancel sits one Tab away.
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
            Their present password stays as it is — it opens the door once more, to the screen where they choose the new
            one, and that screen asks for it. Somebody who signs in with Google is asked for it too; if they do not know
            it, they need a password link.
          </p>
          <label className="flex items-start gap-2 text-sm text-ink-700">
            <input
              type="checkbox"
              checked={withLink}
              disabled={busy}
              onChange={(event) => setWithLinkChoice(event.target.checked)}
              className="mt-1"
            />
            <span>Also issue a password link — shown to you once, on this page, to send them yourself</span>
          </label>
          <p className="text-xs leading-5 text-ink-500">
            Phones on version 0.0.15 or older still say “An administrator set your password” — only a change was
            required.
          </p>
        </div>
      ) : null}
    </FieldDialog>
  );
}

/**
 * CORRECT A NAME OR A SIGN-IN ADDRESS.
 *
 * Only what changed is sent, so a corrected name never re-sends an address and trips a rule about
 * addresses. The address is compared without regard to case because the server stores it lower-cased:
 * re-typing it in capitals is not a change, and sending it would be a write that does nothing.
 */
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
            A corrected address becomes the one they sign in with, and their access moves with it.
          </p>
        </form>
      ) : null}
    </FieldDialog>
  );
}

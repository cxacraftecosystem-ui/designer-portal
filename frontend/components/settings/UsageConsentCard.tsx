"use client";

/**
 * THE OTHER HALF OF THE SIGN-IN TURNSTILE: where a person takes it back.
 *
 * ── WHY THIS CARD IS WHAT MAKES THE GATE AT THE DOOR DEFENSIBLE ─────────────────────────────────
 *
 * `/login` makes agreeing a condition of access, and a condition of access is NOT freely given
 * consent — the server records that in a column (`usageConsentBasis = REQUIRED_AT_SIGN_IN`) rather
 * than filing a turnstile as a free choice. What turns that from something merely documented into
 * something a person actually retains is this: **withdrawing here costs nothing.** No sign-out, no
 * capability removed, no re-consent demanded on the next request. If withdrawal cost access, the
 * withdrawal would be theatre and the whole flow would be indefensible. Every behaviour on this
 * card is chosen to keep that true:
 *
 *  * the withdraw control does not touch the session, and the card says so;
 *  * after a withdrawal the account is refreshed and the person stays exactly where they were;
 *  * the server's `gate.required` is `false` for a REFUSED account, so nothing anywhere re-asks —
 *    and this card renders the server's own sentence saying that rather than inventing one.
 *
 * ── AND WHY IT IS A LOG AND NOT A TOGGLE ───────────────────────────────────────────────────────
 *
 * A consent that shows only its current value invites a switch, and a switch is a control whose
 * history is its last position. This one shows every dated decision, with the CIRCUMSTANCE beside
 * it — "granted at sign-in on the 3rd, withdrawn in settings on the 9th" is the only shape that can
 * answer what collection was made under. Withdrawing does not erase the earlier grant, and the log
 * is where a reader sees that it did not.
 *
 * ── PLACEMENT ──────────────────────────────────────────────────────────────────────────────────
 *
 * `/settings`, beside Appearance and Accessibility, with no role gate at all. It is an account-owned
 * setting in exactly the way those two are: `GET`/`POST /api/usage/consent` are `get_current_user`
 * and nothing more, because reading and changing your own answer about your own data needs
 * permission from nobody. It is deliberately NOT on `/settings/usage`, which is the admin aggregate
 * — filing a person's own consent behind an admin page would mean asking an administrator what you
 * had agreed to.
 */

import { useCallback, useEffect, useState } from "react";
import { History, ShieldCheck } from "lucide-react";

import { useAuth } from "@/components/AuthProvider";
import { useConfirm } from "@/components/dialogs/ConfirmDialog";
import { UsageConsentDisclosure } from "@/components/settings/UsageConsentNotice";
import { useToast } from "@/components/ui/Toast";
import {
  consentBasisText,
  consentMoment,
  durationText,
  loadMyUsageConsent,
  loadMyUsageTrail,
  recordUsageConsent,
  withdrawUsageConsent,
  daysAgoIso,
  nowIso,
  type MyUsageConsent,
  type MyUsageTrail,
  type UsageConsentDecision,
  type UsageConsentResult
} from "@/lib/usage";

/**
 * The current answer as one line of chrome.
 *
 * THE THREE STATES GET THREE DIFFERENT TREATMENTS AND EACH SAYS WHICH IT IS IN WORDS, because a
 * person who cannot distinguish the tints must still be able to tell "nobody asked you" from "you
 * agreed" from "you declined" — and those are three different facts with three different next
 * moves, not three shades of one.
 */
function AnswerChip({ state }: { state: string }) {
  const copy: Record<string, { label: string; className: string }> = {
    GRANTED: { label: "Agreed", className: "border-success-600/30 bg-success-100 text-success-600" },
    REFUSED: { label: "Declined", className: "border-line-200 bg-surface-50 text-ink-700" },
    NOT_RECORDED: { label: "Not answered", className: "border-amber-500/40 bg-amber-100 text-amber-900" }
  };
  const chosen = copy[state] ?? { label: state, className: "border-line-200 bg-surface-50 text-ink-700" };
  return (
    <span className={`rounded-full border px-2.5 py-0.5 text-xs font-semibold ${chosen.className}`}>{chosen.label}</span>
  );
}

/** One dated decision. Both clocks, and the circumstance, because those three are what make the row
 *  a consent record rather than a boolean with a date on it. */
function DecisionRow({ row }: { row: UsageConsentDecision }) {
  return (
    <li className="grid gap-0.5 border-b border-line-200 py-2 last:border-0">
      <div className="flex flex-wrap items-center gap-2">
        <AnswerChip state={row.decision ?? "NOT_RECORDED"} />
        <span className="text-sm text-ink-900">{consentMoment(row.createdAt)}</span>
        {/* Only where the two differ. `recordedAt` is null when the answer was given straight
            against the server, and printing "server heard it at X, device says X" twice would
            invent a device report that never happened. */}
        {row.recordedAt && row.recordedAt !== row.createdAt ? (
          <span className="text-xs text-ink-500">answered on the device at {consentMoment(row.recordedAt)}</span>
        ) : null}
      </div>
      <p className="text-xs leading-5 text-ink-500">
        {consentBasisText(row.basis)}
        {row.noticeVersion ? ` · notice ${row.noticeVersion}` : ""}
      </p>
      {row.note ? <p className="text-xs leading-5 text-ink-700">{row.note}</p> : null}
    </li>
  );
}

/**
 * WHAT THIS PLATFORM ACTUALLY HOLDS ABOUT YOU, request by request.
 *
 * `GET /usage/me/trail` needs no permission beyond being signed in, and it is what makes the
 * notice's "you can see exactly what we hold about you" true rather than aspirational. It is loaded
 * on demand rather than with the card: it is a real query over the highest-write table in the
 * schema, and nobody opening Settings to change their theme should pay for it.
 *
 * **AN EMPTY LIST IS NEVER LEFT TO SPEAK FOR ITSELF.** "No rows" reads as "you have never used this
 * app", and for an account that has not agreed — or that agreed yesterday and is asking about last
 * week — it is the opposite of the truth: nothing was ever attributed, so there is nothing to find.
 * The server sends a `gate` alongside precisely so the emptiness can be explained, and its sentence
 * is what is rendered here.
 */
function MyTrail() {
  const [trail, setTrail] = useState<MyUsageTrail | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function load() {
    setBusy(true);
    setError(null);
    try {
      setTrail(await loadMyUsageTrail({ from: daysAgoIso(7), to: nowIso() }));
    } catch (err) {
      setError(err instanceof Error ? err.message : "Unable to read your own record.");
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="grid gap-2 rounded-md border border-line-200 bg-surface-50 p-3">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div className="min-w-0">
          <h3 className="text-sm font-medium text-ink-900">What has been recorded about you</h3>
          <p className="text-xs leading-5 text-ink-500">
            The last seven days, newest first. Yours alone — no other account can read it at any rank.
          </p>
        </div>
        <button type="button" className="field-button-secondary" onClick={load} disabled={busy}>
          {busy ? "Reading…" : trail ? "Refresh" : "Show my record"}
        </button>
      </div>

      {error ? <p className="text-sm text-error-600">{error}</p> : null}

      {trail ? (
        <>
          {trail.events.length === 0 ? (
            // The server's own sentence about WHY it is empty, never a bare "no rows".
            <p className="rounded-md border border-line-200 bg-card px-3 py-2 text-sm leading-6 text-ink-700">
              Nothing is recorded against your account in this window. {trail.gate.reason}
            </p>
          ) : (
            <div className="overflow-x-auto">
              <table className="w-full min-w-[520px] text-left text-sm">
                <thead className="border-b border-line-200 text-xs uppercase tracking-wide text-ink-500">
                  <tr>
                    <th className="py-2 pr-3 font-medium">When</th>
                    <th className="py-2 pr-3 font-medium">Screen</th>
                    <th className="py-2 pr-3 font-medium">Method</th>
                    <th className="py-2 pr-3 font-medium">Status</th>
                    <th className="py-2 pr-3 font-medium">Server took</th>
                    <th className="py-2 font-medium">Client</th>
                  </tr>
                </thead>
                <tbody>
                  {trail.events.map((event) => (
                    <tr key={event.id} className="border-b border-line-200 last:border-0">
                      <td className="py-2 pr-3 text-ink-700">{consentMoment(event.at)}</td>
                      <td className="py-2 pr-3 font-mono text-xs text-ink-700">{event.routeTemplate}</td>
                      <td className="py-2 pr-3 text-ink-700">{event.method}</td>
                      <td className="py-2 pr-3 text-ink-700">{event.statusCode}</td>
                      {/* Read off the row. `durationText` never computes an average of an average. */}
                      <td className="py-2 pr-3 text-ink-700">{durationText(event.durationMs)}</td>
                      <td className="py-2 text-ink-700">{event.clientApp}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
          {/* THE CAP, WITH ITS NUMBER, ALWAYS — a list that quietly stops is indistinguishable from
              a person who did nothing else. */}
          <p className="text-xs leading-5 text-ink-500">
            Showing {trail.events.length} of at most {trail.maxRows} rows per page, over{" "}
            {trail.window.days} day{trail.window.days === 1 ? "" : "s"}.
          </p>
          <ul className="grid list-disc gap-1 pl-5 text-xs leading-5 text-ink-500">
            {trail.notes.map((note) => (
              <li key={note}>{note}</li>
            ))}
          </ul>
        </>
      ) : null}
    </div>
  );
}

export function UsageConsentCard() {
  const { refreshMe } = useAuth();
  const { toast } = useToast();
  const confirm = useConfirm();

  const [state, setState] = useState<MyUsageConsent | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const load = useCallback(async () => {
    try {
      setState(await loadMyUsageConsent());
    } catch (err) {
      setError(err instanceof Error ? err.message : "Unable to read your recording answer.");
    }
  }, []);

  useEffect(() => {
    let cancelled = false;
    loadMyUsageConsent()
      .then((result) => {
        if (!cancelled) setState(result);
      })
      .catch((err) => {
        if (!cancelled) setError(err instanceof Error ? err.message : "Unable to read your recording answer.");
      });
    return () => {
      cancelled = true;
    };
  }, []);

  /**
   * Fold a write's answer back into the card, and re-read the session with it.
   *
   * `refreshMe()` IS NOT OPTIONAL. `AppShell` and every screen that will one day branch on
   * `usageConsentGate` read it off the cached `User`; without this the session goes on carrying the
   * answer from before the click, and a person who has just withdrawn is still described as having
   * agreed everywhere except this panel.
   */
  const applyResult = useCallback(
    async (result: UsageConsentResult) => {
      setState((current) =>
        current ? { ...current, consent: result.consent, gate: result.gate, decisions: result.decisions } : current
      );
      await refreshMe();
    },
    [refreshMe]
  );

  async function agree() {
    if (!state) return;
    setBusy(true);
    setError(null);
    const recordedAt = new Date().toISOString();
    try {
      /*
        `OFFERED_IN_SETTINGS`, AND THE CLIENT IS ALLOWED TO SAY SO HERE.

        This is the free-choice door — nothing is gated on the answer, the person is already signed
        in and stays signed in whichever button they press. That is exactly the circumstance the
        basis column exists to distinguish from the turnstile at `/login`, which sends
        `REQUIRED_AT_SIGN_IN`. (A WITHDRAWAL never passes a basis at all: `POST /usage/consent/
        withdraw` supplies it server-side, so no client can file a withdrawal as though it had been
        demanded of somebody.)
      */
      const result = await recordUsageConsent({
        decision: "GRANTED",
        basis: "OFFERED_IN_SETTINGS",
        noticeVersion: state.notice.version,
        recordedAt
      });
      await applyResult(result);
      toast({ title: "Recorded", description: "Your use of the platform is now recorded against your account.", tone: "success" });
    } catch (err) {
      setError(err instanceof Error ? err.message : "Unable to record your answer.");
    } finally {
      setBusy(false);
    }
  }

  async function withdraw() {
    if (!state) return;
    /*
      A CONFIRM AND NOT A BARE BUTTON, BECAUSE THIS DELETES.

      The action is not "stop recording" — it is "stop recording AND delete what is already stored",
      and the second half is irreversible. `tone: "danger"` puts initial focus on Cancel and refuses
      a backdrop dismiss, so no reflex Enter destroys anything. What the note must NOT do is make
      withdrawing sound costly: it costs nothing, and a dialog that implied otherwise would be the
      product discouraging the very thing that makes the sign-in gate defensible.
    */
    const ok = await confirm({
      title: "Stop recording, and delete what is stored?",
      body: "New requests from this account stop being recorded immediately, anything observed and not yet written is thrown away, and the rows already stored for you are deleted.",
      note: "It does not sign you out and removes nothing you can do. Your dated decisions stay in the log below, because a withdrawal must not rewrite the answer earlier collection was made under.",
      confirmLabel: "Withdraw",
      tone: "danger"
    });
    if (!ok) return;

    setBusy(true);
    setError(null);
    try {
      const result = await withdrawUsageConsent({
        noticeVersion: state.notice.version,
        recordedAt: new Date().toISOString()
      });
      await applyResult(result);
      // THE SERVER'S OWN SENTENCE ABOUT WHAT THE DELETE ACTUALLY REACHED. `withdraw()` never raises,
      // so a failed delete is otherwise indistinguishable from a successful one — and the failure
      // sentence tells the person to ask an administrator to re-run it, which a generic
      // "Withdrawn" toast would bury.
      toast({
        title: "Withdrawn",
        description: result.withdrawal?.explanation ?? "Recording has stopped for this account.",
        tone: result.withdrawal && !result.withdrawal.storedDeleteRan ? "error" : "success",
        duration: result.withdrawal && !result.withdrawal.storedDeleteRan ? 0 : undefined
      });
    } catch (err) {
      setError(err instanceof Error ? err.message : "Unable to withdraw.");
    } finally {
      setBusy(false);
    }
  }

  if (error && !state) {
    return (
      <section className="panel p-5">
        <h2 className="font-display font-bold text-ink-900">Recording how you use this platform</h2>
        <p className="mt-2 text-sm text-error-600">{error}</p>
        <button type="button" className="field-button-secondary mt-3" onClick={() => void load()}>
          Try again
        </button>
      </section>
    );
  }

  if (!state) {
    return (
      <section className="panel p-5 text-sm text-ink-500">Reading your recording answer…</section>
    );
  }

  const granted = state.consent.state === "GRANTED";

  /**
   * WHETHER THIS CARD OFFERS A WAY TO SAY YES — AND WHY IT IS NOT SIMPLY `!granted`.
   *
   * **THE BUG THIS REPLACES, STATED SO IT IS NOT REINTRODUCED BY A "SIMPLIFICATION".** The control
   * used to be `granted ? withdraw : agree`, which reads correctly for two of the four states this
   * card can be in and strands the third. An account that agreed to notice `2026-08-30.1` and is
   * looking at `2026-09-…` has `consent.state === "GRANTED"` and `gate.required === true`: the
   * paragraph at the top of this card renders the server's own sentence — *"the notice has changed,
   * so the question is being asked again"* — and the only button underneath it was
   * "Withdraw, and delete what is stored". The card asked a question it gave nobody any way to
   * answer, and the person's only route to a fresh grant was to sign out and meet the turnstile at
   * `/login`, which on a session that lasts weeks may be a fortnight away. Worse, the plausible
   * thing to press when the only control is a red one is the red one — so a notice reword would
   * have harvested withdrawals from people who wanted to agree.
   *
   * **THE WEB HAS NO OTHER RE-ASK, WHICH IS WHAT MAKES THIS THE WHOLE OF IT.** `/login` is the only
   * screen that acts on `usageConsentGate`; nothing in the protected tree blocks on it (Android
   * does, at `UsageConsentGateScreen`, which is why the handset never had this hole). So for a
   * signed-in web session this card IS the re-ask, and a version bump is silently ineffective on
   * the web until this control exists.
   *
   * The four states, and what each one may do:
   *
   *  * NOT_RECORDED — `required`, not granted. Agree. (The turnstile normally gets here first.)
   *  * GRANTED at the CURRENT version — `required` false. Nothing to agree to; withdraw only.
   *  * GRANTED at a STALE version — `required` true AND granted. **Both**: agree to the new text,
   *    or take it back. Recording continues under the old answer meanwhile, which is what
   *    `consent_gate` says and why this is not urgent enough to block anything.
   *  * REFUSED — `required` is deliberately FALSE (the server does not re-ask somebody who has
   *    answered), and agreeing again must still be offered, or a refusal becomes a state rather
   *    than a choice.
   */
  const showAgree = state.gate.required || !granted;

  return (
    <section className="panel p-5">
      <div className="flex items-start gap-2.5">
        <ShieldCheck className="mt-0.5 h-5 w-5 shrink-0 text-purple-700" aria-hidden />
        <div className="min-w-0 flex-1">
          <div className="flex flex-wrap items-center gap-2">
            {/* The server's own title, so this card and the sign-in card are visibly one thing. */}
            <h2 className="font-display font-bold text-ink-900">{state.notice.title}</h2>
            <AnswerChip state={state.consent.state} />
          </div>
          <p className="mt-1 text-sm leading-6 text-ink-700">{state.gate.reason}</p>
        </div>
      </div>

      <dl className="mt-4 grid gap-2 text-sm sm:grid-cols-3">
        <div>
          <dt className="field-label">Answered</dt>
          <dd className="mt-0.5 text-ink-900">{consentMoment(state.consent.at)}</dd>
        </div>
        <div>
          <dt className="field-label">Circumstance</dt>
          {/* THE FIELD THAT MAKES THE RECORD HONEST. A grant collected at the door is a condition of
              access; printing only "Agreed" would let a reader — or a methods section — mistake a
              turnstile for a free choice. */}
          <dd className="mt-0.5 text-ink-900">{consentBasisText(state.consent.basis)}</dd>
        </div>
        <div>
          <dt className="field-label">Notice you answered</dt>
          <dd className="mt-0.5 font-mono text-xs text-ink-900">
            {state.consent.version ?? "—"}
            {state.consent.version && state.consent.version !== state.gate.noticeVersion ? (
              <span className="ml-1 font-sans text-ink-500">(current is {state.gate.noticeVersion})</span>
            ) : null}
          </dd>
        </div>
      </dl>

      <div className="mt-4">
        <UsageConsentDisclosure notice={state.notice} />
      </div>

      <div className="mt-4 flex flex-wrap gap-2">
        {/* FIRST IN DOM ORDER WHEREVER BOTH ARE DRAWN, which is the stale-notice case. The
            affirmative answer to the question this card is asking must be the control a keyboard or
            screen-reader user reaches first; putting the destructive one ahead of it would make
            "delete everything you hold about me" the default response to a reworded paragraph. */}
        {showAgree ? (
          <button type="button" className="field-button" onClick={agree} disabled={busy}>
            {busy ? "Working…" : granted ? "Agree to the updated notice" : "Agree to be recorded"}
          </button>
        ) : null}
        {granted ? (
          // `field-danger` ALONE, never stacked on `field-button-secondary`: it carries its own box
          // (inline-flex, min-h-10, padding, radius), and `cn` in this repo is a plain join rather
          // than tailwind-merge — so two competing paddings would resolve by stylesheet position
          // instead of by the order written here. The disabled treatment is added as utilities
          // because the recipe has none of its own, and utilities beat `@layer components`.
          <button
            type="button"
            className="field-danger disabled:cursor-not-allowed disabled:opacity-60"
            onClick={withdraw}
            disabled={busy}
          >
            {busy ? "Working…" : "Withdraw, and delete what is stored"}
          </button>
        ) : null}
        {/* Stated beside the control rather than only in the notice, because this is the sentence
            somebody hesitating over the button needs, and it is the one that makes the gate at
            sign-in defensible rather than merely documented. */}
        <p className="w-full text-xs leading-5 text-ink-500">{state.notice.withdrawal.costsNothing}</p>
      </div>

      <div className="mt-4 grid gap-3">
        <MyTrail />

        <div className="rounded-md border border-line-200 p-3">
          <div className="flex items-center gap-2">
            <History className="h-4 w-4 shrink-0 text-ink-500" aria-hidden />
            <h3 className="text-sm font-medium text-ink-900">Your decisions</h3>
          </div>
          {state.decisions.length === 0 ? (
            <p className="mt-1 text-sm text-ink-500">
              Nothing recorded yet. Nobody has asked you, which is a different thing from a refusal you never made.
            </p>
          ) : (
            <ul className="mt-1">
              {state.decisions.map((row) => (
                <DecisionRow key={row.id ?? `${row.createdAt}-${row.decision}`} row={row} />
              ))}
            </ul>
          )}
        </div>
      </div>

      {error ? <p className="mt-3 text-sm text-error-600">{error}</p> : null}
    </section>
  );
}

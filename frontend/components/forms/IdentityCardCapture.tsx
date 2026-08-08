"use client";

/**
 * Attach a photograph of an identity card to an identity field, read the number off it, and make the
 * researcher confirm it. The artisan form's twin of the stage form's `IdentityCardReader`.
 *
 * ── THE ONE RULE ──────────────────────────────────────────────────────────────────────────────
 *
 * IT NEVER WRITES THE NUMBER BY ITSELF. `Artisan.aadhaarNumber` is the repository's deduplication
 * key: a unique index enforces it, `/artisans/lookup/aadhaar` checks it mid-form, and a number that
 * is one digit wrong collides with NOBODY. It therefore passes every check this system has, creates
 * exactly the duplicate the field exists to prevent, splits that artisan's attendance and payments
 * across two records for as long as the data set lives, and is masked to "XXXX XXXX 9012" on every
 * surface afterwards so nobody ever reads it back and notices. A misread a researcher glances at and
 * corrects costs three seconds.
 *
 * So the reader produces CANDIDATES, each re-checked here against the same
 * `aadhaarValidationError` the box below it uses, and a number reaches the field only from a click
 * on a button that spells it out.
 *
 * ── THE PHOTOGRAPH IS NEVER STORED, ANYWHERE ──────────────────────────────────────────────────
 *
 * Not uploaded as media, not staged, not put in the outbox, not kept in state after the read. The
 * `File` comes out of a file input, goes into one request body, and the input is cleared. The server
 * keeps nothing either — `scan_identity_card` has no storage path in it at all and says so. A
 * photograph of a national identity document is retained only when a researcher deliberately uploads
 * one through the ordinary media flow, which is a visible act with a record.
 *
 * This is also why the read is NOT queued when the connection is down. A queued identity photograph
 * is one nobody remembers is on the laptop, and this control refuses to create one: with no
 * connection it says so and the number is typed, which is what the form has always supported.
 *
 * ── TWO READERS, AND WHICH ONE IS USED IS THE RESEARCHER'S TO SEE ─────────────────────────────
 *
 * Where the browser has a text recogniser of its own (`lib/identityCardLocal.ts`), the card can be
 * read WITHOUT the photograph leaving this tab, and with no connection at all. Where it has not —
 * which, measured, is every browser on a Windows laptop; see
 * `docs/DECISION-identity-card-ocr-on-web.md` — the reader is the server's, and the photograph goes
 * to a third-party vision model, which is more accurate and is a different thing to consent to.
 *
 * So the choice is made BEFORE the photograph is taken, by a checkbox that appears only when there
 * is a choice to make, rather than by silently falling back afterwards. Falling back would mean a
 * designer who chose the local reader has the picture uploaded anyway the moment the local one came
 * up empty — and it would force this component to hold the `File` after the read, which is the one
 * thing the section above promises it does not do.
 *
 * The local reader offers AADHAAR numbers only, so it is not offered on the Pehchan box at all. A
 * PM Vishwakarma artisan ID has no checksum and no fixed shape, so picking one out of recognised
 * text would nominate the artisan's name with equal confidence — `lib/identityCardText.ts` argues
 * that at length.
 *
 * ── WHY THERE ARE TWO BUTTONS ─────────────────────────────────────────────────────────────────
 *
 * `capture="environment"` opens the rear camera on a phone and is ignored on a desktop browser,
 * which is why the second button exists rather than being a convenience: on a laptop the first
 * button is a file dialog, and on a phone the second one is how a researcher reaches a photograph
 * they took earlier — including one taken in a courtyard with no signal and read once they are back
 * in it, without asking the artisan for the card a second time.
 *
 * Android's `DwIdentityCardControl` is the same control with the same two ways in and the same
 * refusals; its file header carries the on-device-recognition decision that applies to both.
 *
 * ── WHO IS OFFERED IT: THE DESIGNER SET, WHICH IS NOT WHO CAN OPEN THIS FORM ──────────────────
 *
 * `POST /design-workshops/ocr/identity` begins with `_require_designer`, i.e. `can_run_design_workshops`
 * — the SET {Designer, Admin, Master Admin}. The artisan form is guarded by `canCreateRecords`, a
 * RANK THRESHOLD at Researcher and above. Those are different rules and they do not nest: a
 * RESEARCHER and a PROFESSOR can both open this form, and the endpoint answers both 403. A
 * PROFESSOR outranks a designer and is still outside the set — see `deps.can_run_design_workshops`,
 * which argues the point.
 *
 * So this control renders for the designer set ONLY, and `serverOffersRoute` cannot stand in for
 * that check: it probes with a GET, and a GET to this POST-only route answers 405 from the router
 * BEFORE any dependency runs (measured: `curl` against the running API returns 405 with no token at
 * all). The probe therefore says "the route exists" to every account alive.
 *
 * Hiding it is not cosmetic. Without this gate a professor sees "Photograph the card", photographs
 * somebody's Aadhaar card, and the image is uploaded to a third-party vision model before the 403
 * comes back — the photograph is taken and transmitted, and only then refused. That is the exact
 * shape `deps.py` names: the UI offering what the API refuses.
 */

import { useEffect, useId, useRef, useState } from "react";
import { AlertTriangle, Camera, Check, ImageIcon, Loader2, MonitorCheck, X } from "lucide-react";

import { useAuth } from "@/components/AuthProvider";
import {
  DW_OCR_IDENTITY_PATH,
  identityChoices,
  readIdentityCard,
  serverOffersRoute,
  type DwIdentityChoice
} from "@/lib/designWorkshops";
import { browserCanReadCards, readCardTextInBrowser } from "@/lib/identityCardLocal";
import { identityCandidatesFromText } from "@/lib/identityCardText";
import { canRunDesignWorkshops } from "@/lib/permissions";

/** "123456789012" -> "1234 5678 9012", the grouping the card prints and a person proofreads by. */
function display(choice: DwIdentityChoice): string {
  return choice.kind === "AADHAAR" ? choice.value.replace(/(\d{4})(?=\d)/g, "$1 ") : choice.value;
}

export function IdentityCardCapture({
  kind,
  targetLabel,
  currentValue,
  aadhaarProblem,
  onUse,
  disabled
}: {
  /** Which list of candidates this field can hold. Never "ANY" here — the artisan form has one box per card. */
  kind: "AADHAAR" | "PEHCHAN";
  targetLabel: string;
  /**
   * `AadhaarField.aadhaarValidationError`, PASSED IN rather than imported.
   *
   * Not a preference: `AadhaarField` renders this control, so importing its validator here would be
   * a module cycle. Injecting it also makes the contract explicit — there is exactly one checksum on
   * this client, and this component does not get to have an opinion of its own about what a valid
   * Aadhaar number is.
   */
  aadhaarProblem: (digits: string) => string | null;
  /** What the box holds now, so "confirming replaces it" can be said before it happens. */
  currentValue: string;
  onUse: (value: string) => void;
  disabled?: boolean;
}) {
  const baseId = useId();
  const cameraRef = useRef<HTMLInputElement>(null);
  const pickerRef = useRef<HTMLInputElement>(null);
  const [offered, setOffered] = useState<boolean | null>(null);
  const [reading, setReading] = useState(false);
  const [choices, setChoices] = useState<DwIdentityChoice[]>([]);
  const [problem, setProblem] = useState<string | null>(null);
  /** Whether THIS BROWSER can recognise text itself. Null while the one probe is in flight. */
  const [browserReads, setBrowserReads] = useState<boolean | null>(null);
  /**
   * Ticked = read it here, send nothing. Defaults ON wherever the choice exists, because the
   * default is the one a designer gets without deciding, and "the photograph does not leave this
   * computer" is the safer thing to do by accident.
   */
  const [readHere, setReadHere] = useState(true);
  /** Named on the candidate panel, so "where was this read" is never a thing to remember. */
  const [readLocally, setReadLocally] = useState(false);
  // Mirrored from `_require_designer`, never re-derived: the SET, not the rank ladder this form's
  // own guard uses. See the header — a professor passes `canCreateRecords` and fails this one.
  // `user` is null while `/me` is in flight, and `canRunDesignWorkshops(null)` is false, so the
  // control appears only once the answer is known rather than flashing for everybody first.
  const { user } = useAuth();
  const permitted = canRunDesignWorkshops(user);

  /**
   * Whether this deployment can read a card at all, probed once per tab.
   *
   * Offered as a button that 503s, this would be indistinguishable from a card the reader could not
   * make out — and the researcher would photograph it four more times before giving up. The route is
   * newer than several of the servers this client talks to, so "absent" is a normal answer.
   *
   * NOT RUN for an account the route would refuse. The probe is a GET against a POST-only route, so
   * it reports 405 — "the route is there" — for every signed-in account regardless of role, and
   * spending a request to be told something that cannot gate anything is the least of it: the answer
   * is cached per path for the tab, so a probe fired here would also be the one a designer's later
   * screen reads.
   */
  useEffect(() => {
    if (!permitted) return;
    let cancelled = false;
    serverOffersRoute(DW_OCR_IDENTITY_PATH)
      .then((available) => {
        if (!cancelled) setOffered(available);
      })
      .catch(() => {
        if (!cancelled) setOffered(false);
      });
    return () => {
      cancelled = true;
    };
  }, [permitted]);

  /**
   * Whether this BROWSER can read a card, asked once and never assumed from a specification.
   *
   * Cheap, local and cached for the tab by `browserCanReadCards` — the Aadhaar box and the Pehchan
   * box mounting in the same tick share one probe. Not gated on `permitted`, unlike the route probe
   * above: it costs no request and nothing to be told.
   */
  useEffect(() => {
    let cancelled = false;
    browserCanReadCards()
      .then((available) => {
        if (!cancelled) setBrowserReads(available);
      })
      .catch(() => {
        if (!cancelled) setBrowserReads(false);
      });
    return () => {
      cancelled = true;
    };
  }, []);

  // The local reader produces Aadhaar numbers and nothing else — see the header — so the Pehchan
  // box never offers it, and never offers the choice either.
  const localUsable = browserReads === true && kind === "AADHAAR";
  const useLocal = localUsable && readHere;

  // After every hook, never before one: this component's state and the two file inputs must keep
  // their slots for the life of the mount.
  //
  // BOTH ANSWERS ARE WAITED FOR, not just one. The control is worth showing when EITHER reader can
  // answer — a deployment with no vision provider configured still has a browser — but the local
  // probe resolves in milliseconds and the route probe is a round trip, so rendering on the first
  // answer would show "Read on this computer" and then grow a checkbox under the researcher's
  // cursor a second later. Same reason `permitted` waits for `/me`.
  if (!permitted || offered === null || browserReads === null) return null;
  if (offered !== true && !localUsable) return null;

  /** Both readers end here, so a candidate is filtered identically however it was found. */
  function offer(found: DwIdentityChoice[], locally: boolean, rejected: number) {
    if (found.length) {
      setReadLocally(locally);
      setChoices(found);
      return;
    }
    if (rejected > 0) {
      // The COUNT, never the values — a rejected reading is still somebody's misread identity
      // number — and it names a different next action from "nothing was found at all".
      setProblem(
        `${rejected} number(s) were read off that card and every one failed its checksum, so at least one digit was wrong in each. Take another photograph in better light with no glare across the digits, or type the number in.`
      );
      return;
    }
    setProblem(
      `No ${kind === "AADHAAR" ? "Aadhaar" : "Pehchan"} number could be read from that photograph. Fill the frame with the card, hold it flat and try again — or type the number in.` +
        // Naming the other reader only when there IS one, and only when it is the one not just
        // tried. The photograph is gone by now (see `read`), so this is an instruction to take
        // another one — which is what "try again" already meant, with one box to untick first.
        (locally && offered === true
          ? " The reader on the server handles a worn or angled card better: untick “Read it on this computer” and photograph it again to use it."
          : "")
    );
  }

  async function read(input: HTMLInputElement | null) {
    const file = input?.files?.[0];
    // Cleared IMMEDIATELY, and before the await: the input must not keep a handle on a photograph of
    // an identity card once its bytes have been read, and clearing it is also what lets the same
    // file be chosen twice in a row (a `change` event does not fire for an identical value).
    if (input) input.value = "";
    if (!file) return;
    setReading(true);
    setProblem(null);
    setChoices([]);
    try {
      if (useLocal) {
        const outcome = await readCardTextInBrowser(file);
        if (!outcome.ok) {
          setProblem(
            outcome.reason === "undecodable"
              ? // Not "the card could not be read". The commonest cause is an iPhone HEIC, and
                // sending somebody back to re-photograph a card that was fine is the worse answer.
                "This browser could not open that picture. Photographs from an iPhone are often HEIC, which most browsers cannot read — re-save it as JPEG or PNG, or type the number in."
              : "This computer could not read that photograph. Type the number in instead."
          );
          return;
        }
        // The rule that decides what may be offered is the pure module's, and it is the same rule
        // the server applies to its own recogniser's text — pinned against the server's verbatim
        // output by `e2e/identity-card-web-unit.spec.ts`.
        const reading = identityCandidatesFromText(outcome.text, aadhaarProblem);
        offer(
          reading.aadhaar.map((value) => ({ value, kind: "AADHAAR" as const, confidence: null })),
          true,
          reading.rejectedCount
        );
        return;
      }
      const result = await readIdentityCard(file);
      offer(identityChoices(result, kind, aadhaarProblem), false, result.rejectedAadhaarCount ?? 0);
    } catch (error) {
      setProblem(
        typeof navigator !== "undefined" && !navigator.onLine
          ? "The connection dropped before the card could be read. Nothing was sent and nothing was kept. Type the number in, or try again where there is signal."
          : error instanceof Error
            ? `That card could not be read: ${error.message}`
            : "That card could not be read. Type the number in instead."
      );
    } finally {
      setReading(false);
    }
  }

  function open(ref: React.RefObject<HTMLInputElement | null>) {
    // Only the SERVER reader needs a connection. When the card is being read in this tab there is
    // nothing to send, nothing to queue and nothing to wait for, so the refusal below would be a
    // capability withheld for no reason — which is the whole of what reading here buys.
    if (!useLocal && typeof navigator !== "undefined" && !navigator.onLine) {
      setProblem(
        "Reading a card needs a connection and there is none. Type the number in now — and if you photograph the card, you can come back here in signal and check what you typed against it. Nothing is queued: a queued identity photograph is one nobody remembers is on the laptop."
      );
      return;
    }
    setProblem(null);
    ref.current?.click();
  }

  return (
    <div className="grid gap-2 rounded-md border border-line-200 bg-surface-50 p-3">
      <p className="text-xs leading-5 text-ink-500">
        Photograph the card, or choose a photograph of it, and the number is read off it. You check it against the card
        and confirm before anything is written into {targetLabel}.{" "}
        {useLocal
          ? "The photograph is read on this computer and is not sent anywhere or stored."
          : "The photograph is not stored — here or on the server."}
      </p>

      <div className="flex flex-wrap gap-2">
        <button type="button" className="field-button-secondary" disabled={disabled || reading} onClick={() => open(cameraRef)}>
          {reading ? <Loader2 className="h-4 w-4 animate-spin" aria-hidden /> : <Camera className="h-4 w-4" aria-hidden />}
          Photograph the card
        </button>
        <button type="button" className="field-button-secondary" disabled={disabled || reading} onClick={() => open(pickerRef)}>
          <ImageIcon className="h-4 w-4" aria-hidden />
          Choose a photo
        </button>
      </div>

      {/*
        THE CHOICE IS SHOWN ONLY WHERE THERE IS ONE. A checkbox with one reachable state is a
        control that teaches a researcher to ignore controls. With both readers present it is a real
        decision — where the picture goes, against how well the number is read — and it is made
        before the camera opens rather than discovered afterwards.
      */}
      {localUsable && offered === true ? (
        <label className="flex items-start gap-2 text-xs leading-5 text-ink-700">
          <input
            type="checkbox"
            className="mt-0.5 h-3.5 w-3.5 shrink-0 accent-purple-700"
            checked={readHere}
            disabled={disabled || reading}
            onChange={(event) => setReadHere(event.currentTarget.checked)}
            data-testid="identity-read-here"
          />
          <span>
            Read it on this computer — the photograph is not sent anywhere, and this works with no connection. Unticked,
            the photograph is sent to the reader on the server, which reads a worn or angled card better.
          </span>
        </label>
      ) : null}

      {localUsable && offered !== true ? (
        <p className="flex items-start gap-2 text-xs leading-5 text-ink-500">
          <MonitorCheck className="mt-0.5 h-3.5 w-3.5 shrink-0" aria-hidden />
          <span>Read on this computer. The photograph is not sent anywhere, and this works with no connection.</span>
        </p>
      ) : null}

      {/*
        Two inputs rather than one with a toggled attribute: `capture` is read by the browser when the
        picker OPENS, and React setting it in the same tick as the click has no defined ordering. Both
        are `sr-only` rather than `hidden` — a `display: none` input cannot be clicked programmatically
        in every browser. Neither carries a `name`, so nothing here can ever reach FormData.
      */}
      <input
        ref={cameraRef}
        id={`${baseId}-camera`}
        type="file"
        accept="image/*"
        capture="environment"
        className="sr-only"
        tabIndex={-1}
        aria-hidden="true"
        onChange={(event) => void read(event.currentTarget)}
      />
      <input
        ref={pickerRef}
        id={`${baseId}-picker`}
        type="file"
        accept="image/*"
        className="sr-only"
        tabIndex={-1}
        aria-hidden="true"
        onChange={(event) => void read(event.currentTarget)}
      />

      {choices.length ? (
        <div className="grid gap-2 rounded-md border border-purple-300 bg-card p-3">
          <p className="text-xs font-medium uppercase tracking-wide text-ink-500">
            {choices.length === 1 ? "Candidate — not saved yet" : `${choices.length} candidates — none saved yet`}
          </p>
          <p className="flex items-start gap-2 rounded-md border border-amber-500 bg-amber-100 px-2 py-1.5 text-xs leading-5 text-amber-800">
            <AlertTriangle className="mt-0.5 h-3.5 w-3.5 shrink-0" aria-hidden />
            <span>
              Read it off the card itself, not off this screen. A single wrong digit produces a number that belongs to
              nobody, so it creates a second record for an artisan already in the system and nothing downstream can
              detect it.
            </span>
          </p>
          {currentValue ? (
            <p className="text-xs leading-5 text-amber-800">
              {targetLabel} already holds “{currentValue}”. Confirming replaces it.
            </p>
          ) : null}
          <div className="grid gap-2">
            {choices.map((choice) => (
              <div key={choice.value} className="grid gap-1">
                <button
                  type="button"
                  className="field-button justify-self-start"
                  disabled={disabled}
                  onClick={() => {
                    onUse(choice.value);
                    setChoices([]);
                  }}
                >
                  <Check className="h-4 w-4" aria-hidden />
                  {/* The button SAYS the number it will write. "Confirm" would be a button whose
                      effect the researcher has to remember; this is the sentence they agree to. */}
                  This matches the card — use {display(choice)}
                </button>
                <p className="text-xs leading-5 text-ink-500">
                  {choice.kind === "AADHAAR" ? "Aadhaar" : "Pehchan card"}
                  {/* Words, not a percentage: "82%" invites a researcher to treat 82 as good enough
                      and skip the check, which is the one behaviour this panel exists to prevent. */}
                  {choice.confidence !== null ? ` · ${choice.confidence >= 0.85 ? "read clearly" : "read with difficulty"}` : ""}
                  {/* WHERE it was read, stated rather than remembered. The browser's recogniser
                      returns no confidence at all, so without this line the local route would be
                      the one with LESS said about it — and "the photograph never left this
                      computer" is the thing most worth saying. */}
                  {readLocally ? " · read on this computer; the photograph was not sent anywhere" : ""}
                </p>
              </div>
            ))}
          </div>
          <button
            type="button"
            className="inline-flex items-center gap-1 justify-self-start text-xs font-medium text-ink-500 underline"
            onClick={() => setChoices([])}
          >
            <X className="h-3 w-3" aria-hidden />
            None of these — discard
          </button>
        </div>
      ) : null}

      {problem ? <p className="text-xs font-medium leading-5 text-error-600">{problem}</p> : null}
    </div>
  );
}

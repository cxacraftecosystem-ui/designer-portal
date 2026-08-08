"use client";

/**
 * "Read the number from this card" — and the reason it never writes the answer by itself.
 *
 * AN OCR MISREAD OF A NATIONAL IDENTITY NUMBER BECOMES AN ARTISAN'S DEDUPLICATION KEY. That is the
 * whole argument and it is worth spelling out, because "prefill it and let them correct it" is the
 * obvious design and it is the wrong one here.
 *
 * The Aadhaar number is what this repository deduplicates artisans on: `/artisans/lookup/aadhaar`
 * checks it mid-form, a unique index enforces it at the write, and `_drop_unchanged_masked_aadhaar`
 * exists purely so the number can travel safely between the two. A number that is one digit wrong
 * collides with NOBODY. It therefore passes every check this system has, creates exactly the
 * duplicate artisan the field exists to prevent, and — because the same person is now two records —
 * splits their attendance, their products and their payments across both for as long as the data
 * set lives. Nobody finds out, because there is nothing to find: the record looks complete.
 *
 * A misread digit is also the LIKELY outcome rather than the unlucky one. These photographs are
 * taken on a handset, at an angle, in a courtyard, of a laminated card with a hologram across it.
 *
 * So the reader produces a CANDIDATE. It is shown, it is checked against the same Verhoeff checksum
 * the artisan form uses (`aadhaarValidationError`, passed into `identityChoices` rather than
 * re-implemented, so the two cannot come to different conclusions about the same twelve digits), and
 * it is written only when a person has read it against the card in their hand and pressed Confirm.
 *
 * A CANDIDATE THAT FAILS THE CHECKSUM IS NOW REFUSED RATHER THAN OFFERED WITH A WARNING, which is a
 * change from how this read. The old warning could not fire: the server applies the same Verhoeff
 * filter before it answers, so it has never sent a 12-digit run that fails, and a Pehchan code is
 * not twelve digits. Anything that reaches the refusal is a transport or shape problem — not a card
 * a designer can improve by photographing it again — and offering it under an amber banner invited
 * exactly the confirmation the banner was warning against.
 *
 * WHERE THE BROWSER HAS A RECOGNISER OF ITS OWN, THE PHOTOGRAPH NEED NOT LEAVE THE TAB. That is a
 * different thing to consent to from sending a national identity document to a third-party vision
 * model, so it is a visible choice made before the read rather than a silent fallback after one —
 * the same control, and the same default, as the artisan form's `IdentityCardCapture`. Unlike that
 * form, this one already holds the `File` (it came from the media field on this visit), so a
 * designer whose local read found nothing simply unticks and presses the button again.
 *
 * The local reader offers AADHAAR numbers only. `artisanCardNo` takes whichever card the artisan
 * produced, so a Pehchan card has to go to the server: there is no checksum on a PM Vishwakarma ID
 * and no way to tell one out of recognised text from the artisan's own name.
 * `docs/DECISION-identity-card-ocr-on-web.md` carries the measurements behind all of this, including
 * why no recogniser is bundled to make the local route universal.
 */

import { useEffect, useState } from "react";
import { AlertTriangle, Check, Loader2, MonitorCheck, ScanLine, X } from "lucide-react";

import { aadhaarValidationError } from "@/components/forms/AadhaarField";
import { DW_OCR_IDENTITY_PATH, identityChoices, readIdentityCard, serverOffersRoute } from "@/lib/designWorkshops";
import { browserCanReadCards, readCardTextInBrowser } from "@/lib/identityCardLocal";
import { identityCandidatesFromText } from "@/lib/identityCardText";
import type { MediaFile } from "@/lib/types";

type Candidate = {
  number: string;
  kind: string;
  confidence: number | null;
  /** The name of the photograph it came from, so a designer with three cards attached knows which. */
  source: string;
  /** True when it was recognised in this tab and the photograph was never sent. Stated, not implied. */
  local: boolean;
};

export function IdentityCardReader({
  files,
  originals,
  targetLabel,
  currentValue,
  onConfirm,
  disabled
}: {
  /** The images already linked to the media field this reader sits under. */
  files: MediaFile[];
  /** The `File` each linked id came from — see the note in FieldInput's MediaField for why. */
  originals: Record<string, File>;
  targetLabel: string;
  currentValue: string;
  onConfirm: (digits: string) => void;
  disabled?: boolean;
}) {
  const [offered, setOffered] = useState<boolean | null>(null);
  const [reading, setReading] = useState(false);
  const [candidate, setCandidate] = useState<Candidate | null>(null);
  const [problem, setProblem] = useState<string | null>(null);
  /** Whether THIS BROWSER can recognise text itself. Null while the one probe is in flight. */
  const [browserReads, setBrowserReads] = useState<boolean | null>(null);
  /**
   * Ticked = read it here, send nothing. Defaults ON wherever the choice exists: the default is
   * what a designer gets without deciding, and "the photograph does not leave this computer" is the
   * safer thing to do by accident.
   */
  const [readHere, setReadHere] = useState(true);

  /**
   * Whether this deployment can read a card at all, probed once per tab.
   *
   * Offered as a button that 404s, this would be indistinguishable from a card the reader could not
   * make out — and the designer would photograph it four more times before giving up. The route is
   * newer than several of the servers this client talks to, so "absent" is a normal answer.
   */
  useEffect(() => {
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
  }, []);

  /** Whether this BROWSER can recognise text itself. Local, free, and cached for the tab. */
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

  const readable = files.filter((file) => file.mediaType === "IMAGE");
  const useLocal = browserReads === true && readHere;
  // Either reader is a reason to be here. A deployment with no vision provider configured still has
  // whatever the browser brought.
  if ((offered !== true && browserReads !== true) || !readable.length) return null;

  async function read(media: MediaFile) {
    setReading(true);
    setProblem(null);
    setCandidate(null);
    try {
      const source = originals[media.id];
      if (!source) {
        // The photograph was attached on an earlier visit, so its bytes are no longer in this tab.
        // Refetching them means a cross-origin read of a presigned URL, which needs a bucket CORS
        // rule this app does not require for anything else — so the honest answer is to say what to
        // do rather than to fail in a way that looks like the reader being broken.
        setProblem(
          "This photograph was attached in an earlier session, so its file is no longer in this tab. Re-attach it to read the number from it, or type the number in."
        );
        return;
      }
      if (useLocal) {
        const outcome = await readCardTextInBrowser(source);
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
        // Same rule as the server applies to its own recogniser's text, pinned against the server's
        // verbatim output by `e2e/identity-card-web-unit.spec.ts`.
        const found = identityCandidatesFromText(outcome.text, aadhaarValidationError);
        const [first] = found.aadhaar;
        if (!first) {
          setProblem(
            found.rejectedCount > 0
              ? `${found.rejectedCount} number(s) were read off that card and every one failed its checksum, so at least one digit was wrong in each. Take another photograph in better light with no glare across the digits, or type the number in.`
              : // Naming the second route matters here and not on the artisan form: this reader
                // still holds the file, so unticking and pressing again costs nothing.
                offered === true
                ? "No Aadhaar number could be read on this computer. Untick “Read it on this computer” and press again to send the photograph to the reader on the server, which reads a worn card better and can also read a Pehchan card — or type the number in."
                : "No Aadhaar number could be read on this computer. Take another photograph with the whole card in frame and no glare across the digits, or type the number in."
          );
          return;
        }
        setCandidate({
          number: first,
          kind: "AADHAAR",
          // The browser's recogniser returns no confidence, and inventing one would be worse than
          // saying nothing: the panel would print a word a designer could lean on.
          confidence: null,
          source: media.originalFilename,
          local: true
        });
        return;
      }

      const result = await readIdentityCard(source);
      // `identityChoices` reads the keys the server ACTUALLY sends and re-applies the checksum here.
      // What used to stand in this place — `(result.number ?? "")` — named a key the endpoint has
      // never returned, so this branch reported every successful read as an unreadable card. The
      // field is `artisanCardNo`, filled from whichever card the artisan produced, so both lists are
      // offered: "ANY".
      const [best] = identityChoices(result, "ANY", aadhaarValidationError);
      if (!best) {
        const rejected = result.rejectedAadhaarCount ?? 0;
        setProblem(
          rejected > 0
            ? // The COUNT, never the values — a rejected candidate is still somebody's misread
              // identity number. It also names a different next action from "nothing was found".
              `${rejected} number(s) were read off that card and every one failed its checksum, so at least one digit was wrong in each. Take another photograph in better light with no glare across the digits, or type the number in.`
            : "No number could be read from that photograph. Take another with the whole card in frame and no glare across the digits, or type the number in."
        );
        return;
      }
      setCandidate({
        number: best.value,
        kind: best.kind,
        confidence: best.confidence,
        source: media.originalFilename,
        local: false
      });
    } catch (error) {
      setProblem(
        error instanceof Error ? `The card could not be read: ${error.message}` : "The card could not be read."
      );
    } finally {
      setReading(false);
    }
  }

  // Grouped 4-4-4 only for an Aadhaar number, which is how the card prints it. A Pehchan code has no
  // such grouping and is shown exactly as it will be stored, so what the button says and what the
  // field gets cannot differ.
  const grouped = candidate ? (candidate.kind === "AADHAAR" ? candidate.number.replace(/(\d{4})(?=\d)/g, "$1 ") : candidate.number) : "";

  return (
    <div className="grid gap-2 rounded-md border border-line-200 bg-surface-50 p-3">
      <div className="flex flex-wrap items-center gap-2">
        <ScanLine className="h-4 w-4 shrink-0 text-ink-500" aria-hidden />
        <span className="text-sm font-medium text-ink-900">Read the number from this card</span>
      </div>
      <p className="text-xs leading-5 text-ink-500">
        The number is read from the photograph and shown here for you to check against the card. Nothing is written into{" "}
        {targetLabel} until you confirm it — a single wrong digit in an identity number does not clash with anybody, so it
        creates a duplicate artisan that nothing downstream can detect.
      </p>

      {/*
        THE CHOICE IS SHOWN ONLY WHERE THERE IS ONE — see the file header. Where only one reader
        exists there is nothing to decide, and a checkbox with one reachable state teaches a designer
        to stop reading checkboxes.
      */}
      {browserReads === true && offered === true ? (
        <label className="flex items-start gap-2 text-xs leading-5 text-ink-700">
          <input
            type="checkbox"
            className="mt-0.5 h-3.5 w-3.5 shrink-0 accent-purple-700"
            checked={readHere}
            disabled={disabled || reading}
            onChange={(event) => setReadHere(event.currentTarget.checked)}
            data-testid="identity-reader-read-here"
          />
          <span>
            Read it on this computer — the photograph is not sent anywhere, and this works with no connection. Unticked,
            the photograph is sent to the reader on the server, which reads a worn or angled card better and is the only
            one that can read a Pehchan card.
          </span>
        </label>
      ) : null}

      {browserReads === true && offered !== true ? (
        <p className="flex items-start gap-2 text-xs leading-5 text-ink-500">
          <MonitorCheck className="mt-0.5 h-3.5 w-3.5 shrink-0" aria-hidden />
          <span>
            Read on this computer. The photograph is not sent anywhere, and this works with no connection — but only an
            Aadhaar number can be read this way.
          </span>
        </p>
      ) : null}

      <div className="flex flex-wrap gap-2">
        {readable.map((media) => (
          <button
            key={media.id}
            type="button"
            className="field-button-secondary"
            disabled={disabled || reading}
            onClick={() => void read(media)}
          >
            {reading ? <Loader2 className="h-4 w-4 animate-spin" aria-hidden /> : <ScanLine className="h-4 w-4" aria-hidden />}
            {readable.length === 1 ? "Read this card" : `Read ${media.originalFilename}`}
          </button>
        ))}
      </div>

      {candidate ? (
        <div className="grid gap-2 rounded-md border border-purple-300 bg-card p-3">
          <p className="text-xs font-medium uppercase tracking-wide text-ink-500">Candidate — not saved yet</p>
          {/* Grouped 4-4-4 exactly as the card prints it. A researcher compares group by group, and a
              dropped digit is visible in "1234 5678 901" in a way it is not in "123456789 01". */}
          <p className="font-mono text-lg tracking-wider text-ink-900">{grouped}</p>
          <p className="text-xs leading-5 text-ink-500">
            Read from {candidate.source}
            {candidate.kind ? ` · looks like a ${candidate.kind.toLowerCase()} card` : ""}
            {/* WORDS, NOT A PERCENTAGE, and the two new panels written alongside this one already say
                why: "82% sure" invites a designer to treat 82 as good enough and skip the comparison,
                which is the one behaviour this panel exists to prevent. There is no threshold at
                which reading the number off the card stops being required, so there is no number
                here to reason about. Matches `IdentityCardCapture` and Android's
                `DwIdentityCardControl` exactly, including the 0.85 boundary. */}
            {candidate.confidence !== null
              ? ` · ${candidate.confidence >= 0.85 ? "read clearly" : "read with difficulty"}`
              : ""}
            {/* WHERE it was read, stated rather than remembered — and the local route has no
                confidence to print, so without this line it would be the one with LESS said about
                it. "The photograph was not sent anywhere" is the part worth saying. */}
            {candidate.local ? " · read on this computer; the photograph was not sent anywhere" : ""}.
          </p>

          <p className="flex items-start gap-2 rounded-md border border-amber-500 bg-amber-100 px-2 py-1.5 text-xs leading-5 text-amber-800">
            <AlertTriangle className="mt-0.5 h-3.5 w-3.5 shrink-0" aria-hidden />
            <span>
              Read it off the card itself, not off this screen. A misread digit produces a number that belongs to nobody,
              so nothing downstream can ever detect it.
            </span>
          </p>

          {currentValue ? (
            <p className="text-xs leading-5 text-amber-800">
              {targetLabel} already holds “{currentValue}”. Confirming replaces it.
            </p>
          ) : null}

          <div className="flex flex-wrap items-center gap-2">
            <button
              type="button"
              className="field-button"
              disabled={disabled}
              onClick={() => {
                onConfirm(candidate.number);
                setCandidate(null);
              }}
            >
              <Check className="h-4 w-4" aria-hidden />
              This matches the card — use it
            </button>
            <button
              type="button"
              className="inline-flex items-center gap-1 text-xs font-medium text-ink-500 underline"
              onClick={() => setCandidate(null)}
            >
              <X className="h-3 w-3" aria-hidden />
              Discard this reading
            </button>
          </div>
        </div>
      ) : null}

      {problem ? <p className="text-xs font-medium leading-5 text-error-600">{problem}</p> : null}
    </div>
  );
}

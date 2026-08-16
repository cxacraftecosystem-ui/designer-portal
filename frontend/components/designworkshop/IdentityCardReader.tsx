"use client";

/**
 * "Read the number from this card" — and the reason it never writes the answer by itself.
 *
 * ── READ THIS FIRST: THE PHOTOGRAPH IS ALREADY STORED WHEN THIS PANEL APPEARS ─────────────────
 *
 * This reader does not take a photograph. It is rendered UNDER a media field and works on images the
 * designer has already attached to it — and attaching to a media field is the ordinary media flow,
 * which by the time this panel is on screen has presigned a PUT, put the bytes in S3 and created a
 * `MediaFile` row through `/media/complete`. So unlike `IdentityCardCapture` on the artisan form,
 * which holds a loose `File` and stores nothing, everything this panel touches is durable already.
 *
 * That is not a detail of the wiring, it is the security fact of this screen: an unmasked identity
 * document is in the repository before anybody has been asked whether it should be. It is worth
 * being exact about why "unmasked" is the word. `artisan_identity.mask_aadhaar` and
 * `records.mask_identity_number` mask the DIGITS on every exported surface and that machinery is
 * sound — but a photograph of the card is those same digits in a form no masking function can
 * reach. It is a JPEG. Nothing downstream will ever redact it.
 *
 * So once a designer has told this panel that a given photograph is an identity card — which is
 * what pressing "Read this card" says, and is the first moment any code here could know it — the
 * panel refuses to let the picture stay on the record by default. It asks, in two words, and the
 * safe one is the one that needs no decision. See `lib/identityPhotoRetention.ts` for the argument
 * and for why DISCARD is a hard delete rather than this file's usual soft one.
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
import { AlertTriangle, Check, Loader2, MonitorCheck, ScanLine, ShieldAlert, Trash2, X } from "lucide-react";

import { aadhaarValidationError } from "@/components/forms/AadhaarField";
import { DW_OCR_IDENTITY_PATH, identityChoices, readIdentityCard, serverOffersRoute } from "@/lib/designWorkshops";
import { browserCanReadCards, readCardTextInBrowser } from "@/lib/identityCardLocal";
import { identityCandidatesFromText } from "@/lib/identityCardText";
import {
  DW_PHOTO_UNDECIDED,
  decideIdentityPhotograph,
  retentionOutcomeSentence,
  type DwPhotoRetention
} from "@/lib/identityPhotoRetention";
import type { MediaFile } from "@/lib/types";

type Candidate = {
  number: string;
  kind: string;
  confidence: number | null;
  /**
   * Which photograph it was read off, so a candidate cannot outlive the file it came from.
   *
   * A designer who deletes the picture and is then still shown a number to confirm has nothing left
   * to check it against — and checking it against the card is the one safeguard between an OCR
   * misread and this repository's deduplication key.
   */
  mediaId: string;
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
  onDiscard,
  disabled
}: {
  /** The images already linked to the media field this reader sits under. */
  files: MediaFile[];
  /** The `File` each linked id came from — see the note in FieldInput's MediaField for why. */
  originals: Record<string, File>;
  targetLabel: string;
  currentValue: string;
  onConfirm: (digits: string) => void;
  /**
   * Drop a photograph the server has just deleted from this field's value.
   *
   * OPTIONAL, AND THE DELETE HAPPENS WITHOUT IT. The bytes and the `MediaFile` row are gone the
   * moment the route answers, which is the property that matters and is not this component's to
   * negotiate; this callback only stops the field from going on referencing an id that no longer
   * resolves. A caller that omits it gets a correct deletion and a tile that says the file is no
   * longer readable, which is honest but is not as good as the tile not being there.
   */
  onDiscard?: (mediaId: string) => void;
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
   * Every photograph declared to be an identity card and still awaiting a decision, oldest first.
   *
   * A LIST AND NOT A SLOT — see the append in `read` for the three-blurred-cards case that a slot
   * loses in silence.
   *
   * ADDED TO WHEN A READ ACTUALLY RAN, whatever it found, and NOT when the panel merely rendered. Before
   * a designer presses "Read this card" nothing here knows the image is a card — `offersIdentityOcr`
   * guesses from a FIELD NAME, and a field named `identityCardPhoto` on a workshop whose designer
   * attached a portrait to it by mistake is exactly the case a guess gets wrong. Pressing the button
   * is the first statement by a person that this particular picture is somebody's identity document,
   * and that is what turns keeping it into a decision worth forcing.
   *
   * A failed read counts. The photograph is stored either way, and a card the reader could not make
   * out is still a card.
   */
  const [decidable, setDecidable] = useState<{ mediaId: string; name: string }[]>([]);
  /** Which photograph is mid-decision and which way, so only its own two buttons go busy. */
  const [deciding, setDeciding] = useState<{ mediaId: string; choice: DwPhotoRetention } | null>(null);
  /** What happened to each picture, in the past tense, once its decision landed. */
  const [retentionOutcome, setRetentionOutcome] = useState<string[]>([]);
  const [decisionProblem, setDecisionProblem] = useState<string | null>(null);

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
  // BOTH ANSWERS ARE WAITED FOR. Either reader is a reason to be here — a deployment with no vision
  // provider configured still has whatever the browser brought — but the local probe resolves in
  // milliseconds and the route probe is a round trip, so rendering on the first answer would grow a
  // checkbox under the designer's cursor a second after the panel appeared.
  if (offered === null || browserReads === null) return null;
  if ((offered !== true && browserReads !== true) || !readable.length) return null;

  /**
   * Keep this photograph on the record, or delete it outright.
   *
   * The button is what makes the decision, and this is the only place either outcome is reachable
   * from — there is no "later", no draft, and nothing that resolves on unmount. A decision that
   * could be deferred is a decision most people defer, and deferring means keeping.
   */
  async function decide(mediaId: string, choice: DwPhotoRetention) {
    setDeciding({ mediaId, choice });
    setDecisionProblem(null);
    try {
      const result = await decideIdentityPhotograph(mediaId, choice);
      setRetentionOutcome((current) => [...current, retentionOutcomeSentence(result)]);
      // Only on a DELETE the server actually performed. `deleted` is the server's word for "the row
      // and the object are gone", so a client that dropped the reference on anything weaker would
      // hide a photograph that is still there — the precise failure this whole feature is against.
      if (result.deleted) {
        onDiscard?.(mediaId);
        // The candidate came off some photograph; if it came off THIS one it must go with it. The
        // number stays in the field if it was already confirmed — deleting the picture was never a
        // statement about the digits — but an unconfirmed candidate read off a file that no longer
        // exists is something a designer can no longer check against anything.
        setCandidate((current) => (current && current.mediaId === mediaId ? null : current));
      }
      // Answered, so it leaves the queue whichever way it was answered.
      setDecidable((current) => current.filter((entry) => entry.mediaId !== mediaId));
    } catch (error) {
      setDecisionProblem(
        error instanceof Error
          ? // NAMES THE STATE THE RECORD IS IN, which for the delete half is "still stored". A
            // designer who reads "could not be deleted" and nothing else has no way to know whether
            // to try again or whether it half-worked; the server refuses the whole request rather
            // than half-deleting, so this can say so.
            `That decision could not be recorded, so the photograph is exactly as it was — still stored on this record. ${error.message}`
          : "That decision could not be recorded, so the photograph is exactly as it was — still stored on this record. Try again."
      );
    } finally {
      setDeciding(null);
    }
  }

  async function read(media: MediaFile) {
    setReading(true);
    setProblem(null);
    setCandidate(null);
    setDecisionProblem(null);
    // THE DECLARATION, and the whole trigger for the decision block below: pressing this button on
    // THIS photograph is a person saying it is an identity card. Recorded before the source check on
    // purpose — a card attached on an earlier visit cannot be re-read in this tab, but it is still a
    // stored identity document and still the designer's to keep or delete.
    //
    // APPENDED, NEVER REPLACED, and de-duplicated by id.
    // A designer with three cards attached reads the first, gets a candidate, reads the second
    // because the first was blurred, and reads the third. If this slot held one photograph, the
    // first two decisions would have been silently dropped the moment the next read began — two
    // unmasked identity documents left on the record by a control whose entire purpose is to stop
    // exactly that. Every photograph a person has declared to be an identity card stays on this
    // list until it is answered for.
    setDecidable((current) =>
      current.some((entry) => entry.mediaId === media.id)
        ? current
        : [...current, { mediaId: media.id, name: media.originalFilename }]
    );
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
          mediaId: media.id,
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
        mediaId: media.id,
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
        creates a duplicate artisan that nothing downstream can detect. The photographs listed above were uploaded to
        this record when you attached them; after a read you are asked whether to keep or delete the one you read.
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

      {/*
        ── WHAT HAPPENS TO THE PICTURE ────────────────────────────────────────────────────────────

        A SEPARATE BLOCK FROM THE CANDIDATE ABOVE, AND DELIBERATELY BELOW IT. Two decisions are
        being asked for and they are about different objects: "is this the number on the card"
        (which writes a field) and "should this repository keep a photograph of somebody's identity
        document" (which deletes a file, or does not). Folded together, the second would be read as
        a footnote to the first and answered by whichever button was nearer — and the candidate
        block already has a link reading "Discard this reading", which discards a NUMBER and nothing
        else. So this block names its object in every string it contains: the word is "photograph".

        IT OUTLIVES THE CANDIDATE. Confirming the number clears the candidate panel; the photograph
        is still on the record, so this stays until it is answered. That is the point — the moment a
        designer is most likely to walk away is the moment the number lands, which is exactly when
        the picture would otherwise be quietly retained.

        ONE BLOCK PER UNDECIDED PHOTOGRAPH, and they accumulate. A designer who reads three cards
        because the first two were blurred has made three declarations that a picture is an identity
        document, and every one of them is still on the record. A single block would have shown the
        last and lost the other two in silence.
      */}
      {decidable.map((entry) => {
        const busy = deciding?.mediaId === entry.mediaId ? deciding.choice : null;
        return (
          <div key={entry.mediaId} className="grid gap-2 rounded-md border border-amber-500 bg-amber-100 p-3">
            <p className="flex items-start gap-2 text-xs font-medium leading-5 text-amber-800">
              <ShieldAlert className="mt-0.5 h-3.5 w-3.5 shrink-0" aria-hidden />
              <span>
                {/* The filename in every block, because a designer who read three cards is looking
                    at three of these and has no other way to tell which is which. */}
                What happens to “{entry.name}”? {DW_PHOTO_UNDECIDED}
              </span>
            </p>
            <div className="flex flex-wrap items-center gap-2">
              {/*
                THE SAFE ANSWER IS THE PRIMARY BUTTON, and it is first in the DOM so it is also first
                for a keyboard and for a screen reader. Nothing here is preselected and nothing
                happens on a timer: an identity document is not deleted because a designer looked
                away, and it is not kept because they did either. What the ordering buys is that the
                answer nearest to hand is the one that keeps nothing.

                Only THIS photograph's buttons go busy while its own request is in flight — a single
                shared flag would grey out the decisions on the other cards, which is how one slow
                request turns into three undecided identity documents.
              */}
              <button
                type="button"
                className="field-button"
                disabled={disabled || busy !== null}
                onClick={() => void decide(entry.mediaId, "DISCARD")}
                data-testid="identity-photo-discard"
              >
                {busy === "DISCARD" ? (
                  <Loader2 className="h-4 w-4 animate-spin" aria-hidden />
                ) : (
                  <Trash2 className="h-4 w-4" aria-hidden />
                )}
                Delete this photograph
              </button>
              <button
                type="button"
                className="field-button-secondary"
                disabled={disabled || busy !== null}
                onClick={() => void decide(entry.mediaId, "STORE")}
                data-testid="identity-photo-store"
              >
                {busy === "STORE" ? (
                  <Loader2 className="h-4 w-4 animate-spin" aria-hidden />
                ) : (
                  <Check className="h-4 w-4" aria-hidden />
                )}
                Keep it on this record
              </button>
            </div>
            <p className="text-xs leading-5 text-amber-800">
              {/* Both consequences, stated before the press rather than reported after it.
                  "Deleted" is said in full because this app soft-deletes almost everything and a
                  designer has every reason to assume this is another of those. */}
              Deleting removes the file and the record of it — it cannot be undone, and the number you
              confirm is unaffected. Keeping it records that you chose to, with your name and the time.
            </p>
          </div>
        );
      })}

      {retentionOutcome.map((sentence, index) => (
        <p key={`${index}-${sentence}`} role="status" className="text-xs leading-5 text-ink-500">
          {sentence}
        </p>
      ))}
      {decisionProblem ? (
        <p role="alert" className="text-xs font-medium leading-5 text-error-600">
          {decisionProblem}
        </p>
      ) : null}

      {problem ? <p className="text-xs font-medium leading-5 text-error-600">{problem}</p> : null}
    </div>
  );
}

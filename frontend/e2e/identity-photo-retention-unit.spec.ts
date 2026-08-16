import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import { photographWasNotStored, type DwIdentityOcrResult } from "@/lib/designWorkshops";
import {
  DW_IDENTITY_RETENTION_PATH,
  DW_PHOTO_RETENTION_DEFAULT,
  retentionOutcomeSentence,
  type DwPhotoRetentionResult
} from "@/lib/identityPhotoRetention";

/**
 * Keep the photograph of the identity card, or delete it — and what the browser is allowed to
 * believe about which of those happened.
 *
 * ── WHAT WAS TRUE BEFORE THIS FILE EXISTED ────────────────────────────────────────────────────
 *
 * `IdentityCardReader` reads the number off photographs the designer has ALREADY attached to a
 * media field, and attaching to a media field is the ordinary media flow: a presigned PUT into S3
 * and a `MediaFile` row through `/media/complete`. So on the stage form an unmasked identity
 * document was durably in the repository before anyone had been asked whether it should be. The
 * masking machinery this repository is careful about — `mask_aadhaar`, `mask_identity_number` —
 * masks the DIGITS on every exported surface and cannot touch a JPEG of the same digits.
 *
 * PURE NODE. Every assertion here is about a rule that decides what happens to regulated personal
 * data, and rules like that have to be decidable without a browser or they can only be checked by
 * photographing somebody's Aadhaar card. Two of them are cross-surface pins read straight out of
 * the Python: a client whose idea of "the safe default" or "the route that deletes it" has drifted
 * from the server's is a client that quietly stops deleting things.
 */

const BACKEND = join(__dirname, "..", "..", "backend");
const IDENTITY_OCR_PY = readFileSync(join(BACKEND, "app", "services", "identity_ocr.py"), "utf8");
const DESIGN_WORKSHOP_ROUTES_PY = readFileSync(
  join(BACKEND, "app", "api", "routes", "design_workshops.py"),
  "utf8"
);

test("the safe default is the same word on both sides of the wire", () => {
  /*
    THE ONE PROPERTY THE WHOLE FEATURE RESTS ON, pinned against the server's source rather than
    against a memory of it.

    The server resolves everything it does not recognise — a missing field, a blank string, a
    misspelling, a client that sends a boolean — onto DISCARD, and this client's default has to be
    the identical word or the two disagree about what "no answer" means. A client that defaulted to
    "STORE" while the server defaulted to "DISCARD" would show a designer "kept" and delete the
    file; a client that sent a word the server does not know would show "kept" and ALSO delete it,
    which is the same failure with no way to notice.

    Read out of the Python because the alternative is two constants that agree on the day they are
    written. If this ever fails, do not change the assertion: change whichever side moved.
  */
  expect(IDENTITY_OCR_PY).toContain('DISCARD = "DISCARD"');
  expect(IDENTITY_OCR_PY).toContain('STORE = "STORE"');
  expect(DW_PHOTO_RETENTION_DEFAULT).toBe("DISCARD");
});

test("the route that deletes the photograph is the route the server declares", () => {
  /*
    A 404 on this path is indistinguishable, from inside the panel, from a decision that was
    recorded — the delete button would report a failure the designer would read as "try again", and
    an identity document would stay on the record indefinitely while everybody believed the feature
    worked. The route is declared with the router's `/design-workshops` prefix stripped, which is
    what `apiFetch` adds back, so both halves are asserted separately.
  */
  expect(DESIGN_WORKSHOP_ROUTES_PY).toContain('@router.post("/ocr/identity/retention")');
  expect(DESIGN_WORKSHOP_ROUTES_PY).toContain('router = APIRouter(prefix="/design-workshops"');
  expect(DW_IDENTITY_RETENTION_PATH).toBe("/design-workshops/ocr/identity/retention");
});

test("the read route says in its own payload that it kept nothing", () => {
  /*
    `stored: false` is a LITERAL on the server, not an expression — there is no branch in
    `scan_identity_card` that could evaluate to true, and it is written as a constant precisely so
    that adding a storage path would have to come and change it in the response clients read.

    This pin is what lets `IdentityCardCapture` print "the photograph is not stored" as a fact it
    read rather than a claim it made.
  */
  expect(DESIGN_WORKSHOP_ROUTES_PY).toContain('"stored": False,');
});

test("only an explicit false means the photograph was not stored", () => {
  /*
    THE ABSENT-IS-NOT-FALSE RULE, and it is the opposite of this client's usual generosity. Elsewhere
    in `designWorkshops.ts` a missing key is read forgivingly — `requiresConfirmation` absent is
    read as TRUE, because the safe reading of a missing "you must confirm this" is that you must.

    Here the safe reading runs the other way. A deployment older than the `photograph` block sends
    nothing, and treating that as `stored: false` would put "your photograph was not kept" on screen
    on the strength of a key that was never sent — a promise about regulated data made from an
    absence. Where the server has not said, the panel says nothing.
  */
  expect(photographWasNotStored({ photograph: { stored: false } })).toBe(true);

  // No block at all: an older server. Unknown, therefore not reassured about.
  expect(photographWasNotStored({} as DwIdentityOcrResult)).toBe(false);
  expect(photographWasNotStored({ photograph: null })).toBe(false);
  // The block arrived but the key did not — a proxy that rewrote the body, a partial serialiser.
  expect(photographWasNotStored({ photograph: {} })).toBe(false);
  expect(photographWasNotStored({ photograph: { stored: null } })).toBe(false);
  // And a server that says it DID store it is never reported as having discarded it.
  expect(photographWasNotStored({ photograph: { stored: true } })).toBe(false);
});

test("a deletion is described as a deletion, not as a removal from a list", () => {
  /*
    The word matters more here than it usually would. This app soft-deletes nearly everything it
    holds — `design_workshops.py` opens by saying so, and gives the good reason — so a designer has
    every reason to assume "deleted" means "hidden and recoverable". On this one route it does not:
    the S3 object and the `MediaFile` row are both gone. The sentence therefore names both, in the
    past tense, and separately says the confirmed number is untouched, because the two are easy to
    conflate at the moment the panel appears.
  */
  const sentence = retentionOutcomeSentence({ deleted: true, decision: "DISCARD" });
  expect(sentence).toContain("deleted");
  expect(sentence).toContain("the file and the record of it are both gone");
  expect(sentence).toContain("number you confirmed is unaffected");
});

test("a kept photograph is described as kept, in the name of whoever kept it", () => {
  /*
    A retained identity document that cannot be traced to the person who chose to retain it is the
    same problem with a longer paper trail — so the name the SERVER stamped is what the sentence
    prints, never the signed-in user this tab happens to think it is. The two are the same account
    in every ordinary case and are not in the one that matters: a session that has been switched, or
    a stamp that came back from a retry issued under a different token.
  */
  const kept: DwPhotoRetentionResult = {
    deleted: false,
    decision: "STORE",
    retention: { decision: "STORE", decidedById: "usr_1", decidedByName: "Asha Menon", decidedAt: "2026-08-15T11:30:00+00:00" }
  };
  const sentence = retentionOutcomeSentence(kept);
  expect(sentence).toContain("Asha Menon");
  expect(sentence).toContain("kept on this record");
  // The consequence of keeping it, stated at the moment it becomes true rather than in a policy
  // document: this is the one copy of the number that nothing downstream will mask.
  expect(sentence).toContain("unmasked");
});

test("a kept photograph with no name attached still says it was kept", () => {
  /*
    An account with neither a display name nor an email is not something the artisan form can
    produce, but a stamp arriving without `decidedByName` — an older server, a serialiser that
    dropped a null — must not make the panel fall silent about the outcome. The fallback loses the
    NAME and never the FACT, because the fact is the one a designer needs to act on.
  */
  const sentence = retentionOutcomeSentence({ deleted: false, retention: { decidedByName: "   " } });
  expect(sentence).toContain("kept on this record");
  expect(sentence).toContain("unmasked");

  expect(retentionOutcomeSentence({ deleted: false })).toContain("kept on this record");
  // `deleted: false` and a missing `deleted` are the same thing — nothing was destroyed — and
  // neither may be reported as a deletion.
  expect(retentionOutcomeSentence({})).not.toContain("deleted");
});

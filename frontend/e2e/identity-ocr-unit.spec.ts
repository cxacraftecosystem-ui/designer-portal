import { expect, test } from "@playwright/test";

import { aadhaarValidationError } from "@/components/forms/AadhaarField";
import { identityChoices, normalizePehchan, type DwIdentityOcrResult } from "@/lib/designWorkshops";
import { canCreateRecords, canRunDesignWorkshops } from "@/lib/permissions";
import type { User } from "@/lib/types";

/**
 * The browser's reading of `POST /design-workshops/ocr/identity`, against the payload the server
 * actually sends.
 *
 * THE DEFECT THIS PINS. `DwIdentityOcrResult` declared `number`, `documentType`, `name`,
 * `confidence` and `message`. `IdentityOcrResult.payload()` has never returned any of them — it
 * returns `aadhaarCandidates`, `pehchanCandidates`, `rejectedAadhaarCount`, `provider` and
 * `requiresConfirmation`. So `IdentityCardReader` ran `(result.number ?? "").replace(/\D/g, "")`,
 * got "", and showed *"No number could be read from that photograph"* for every card it read
 * PERFECTLY. Nothing threw and nothing was logged; the card simply looked unreadable. Android's
 * `DwIdentityOcrDto` had the identical bug against the identical payload, and
 * `DwIdentityOcrWireTest` is the same assertion on that side.
 *
 * LIVE_PAYLOAD is the verbatim output of `IdentityOcrResult(...).payload()` run under the backend
 * venv, with a Verhoeff-valid test number in place of the digits. These assertions are on the
 * decoding and the filter — what decides whether a national identity number is offered to a
 * researcher at all — and they are decidable without a browser.
 */

const LIVE_PAYLOAD: DwIdentityOcrResult = JSON.parse(`
{
  "aadhaarCandidates": [
    {
      "value": "234567890124",
      "kind": "AADHAAR",
      "confidence": 0.8,
      "masked": "XXXX XXXX 0124"
    }
  ],
  "pehchanCandidates": [],
  "rejectedAadhaarCount": 0,
  "provider": "gemini",
  "requiresConfirmation": true
}
`);

test("the live payload yields the candidate the reader used to miss entirely", () => {
  const choices = identityChoices(LIVE_PAYLOAD, "AADHAAR", aadhaarValidationError);
  expect(choices).toHaveLength(1);
  expect(choices[0].value).toBe("234567890124");
  expect(choices[0].kind).toBe("AADHAAR");
  expect(choices[0].confidence).toBe(0.8);

  // The shape the client used to expect carries nothing — kept as a regression witness, so that
  // "restoring" the old key names from a stale branch fails here and says why.
  expect(identityChoices({ number: "234567890124" } as DwIdentityOcrResult, "AADHAAR", aadhaarValidationError)).toEqual([]);
});

test("a candidate that fails the checksum is refused, not offered with a warning", () => {
  // The server applies Verhoeff before it answers, so in a healthy system this rejects nothing. If it
  // ever fires, the two ports of the rule have drifted — and the moment to find that out is before
  // the number is offered, not after it has become a deduplication key. The second number below is
  // one digit off the first: twelve digits, starts 2-9, belongs to nobody. That is the exact shape
  // of an OCR misread, and the exact shape a duplicate artisan is made of.
  const result: DwIdentityOcrResult = {
    aadhaarCandidates: [{ value: "234567890124" }, { value: "234567890123" }],
    pehchanCandidates: []
  };
  const choices = identityChoices(result, "AADHAAR", aadhaarValidationError);
  expect(choices.map((choice) => choice.value)).toEqual(["234567890124"]);
});

test("each field is offered only the candidates it can hold", () => {
  const result: DwIdentityOcrResult = {
    aadhaarCandidates: [{ value: "2345 6789 0124", kind: "AADHAAR" }],
    pehchanCandidates: [{ value: "pm-vw/12345", kind: "PEHCHAN" }]
  };

  // Spacing as the card prints it is stripped before anything is offered: "2345 6789 0124" and
  // "234567890124" are one number, and the unique index that deduplicates artisans does not know it.
  expect(identityChoices(result, "AADHAAR", aadhaarValidationError).map((c) => c.value)).toEqual(["234567890124"]);
  // A Pehchan code has no checksum, so normalisation to one spelling is the only defence there is.
  expect(identityChoices(result, "PEHCHAN", aadhaarValidationError).map((c) => c.value)).toEqual(["PMVW12345"]);
  // `artisanCardNo` on the stage form takes whichever card the artisan produced — Aadhaar first.
  expect(identityChoices(result, "ANY", aadhaarValidationError).map((c) => c.kind)).toEqual(["AADHAAR", "PEHCHAN"]);
});

test("the same number read twice is offered once, and a code too short to be a card is refused", () => {
  const twice: DwIdentityOcrResult = {
    aadhaarCandidates: [{ value: "234567890124" }, { value: "2345-6789-0124" }]
  };
  expect(identityChoices(twice, "AADHAAR", aadhaarValidationError)).toHaveLength(1);

  // The same 4–32 bound `pehchan_error` applies on the server.
  expect(identityChoices({ pehchanCandidates: [{ value: "AB" }] }, "PEHCHAN", aadhaarValidationError)).toEqual([]);
  expect(normalizePehchan("pm vw/123 45")).toBe("PMVW12345");
});

test("the form's guard and the reader's guard are different rules, and they do not nest", () => {
  /*
    WHY THIS LIVES IN THIS FILE. `IdentityCardCapture` renders under the Aadhaar and Pehchan boxes on
    the artisan form. That form is reached through `canCreateRecords` — a RANK THRESHOLD at
    Researcher and above. The endpoint the control posts to is `_require_designer`, i.e.
    `can_run_design_workshops`: the SET {Designer, Admin, Master Admin}. A PROFESSOR satisfies the
    first and not the second while OUTRANKING a designer.

    So the control cannot inherit the page's guard, and `serverOffersRoute` cannot stand in for one:
    it probes with a GET against a POST-only route, which answers 405 from the router before any
    dependency runs — measured, with no token at all. Without its own check the button appears for a
    professor, who photographs somebody's Aadhaar card and has it uploaded to a third-party vision
    model before the 403 comes back.

    `backend/tests/test_design_workshop_gate.py` asserts the 403 for RESEARCHER and PROFESSOR by
    name on the other side of the wire; this is the assertion that the browser agrees.
  */
  const as = (role: string) => ({ role } as unknown as User);

  for (const role of ["RESEARCHER", "PROFESSOR"]) {
    expect(canCreateRecords(as(role)), `${role} may open the artisan form`).toBe(true);
    expect(canRunDesignWorkshops(as(role)), `${role} must NOT be offered the card reader`).toBe(false);
  }
  for (const role of ["DESIGNER", "ADMIN", "MASTER_ADMIN"]) {
    expect(canRunDesignWorkshops(as(role)), `${role} is in the set the endpoint admits`).toBe(true);
  }
  // Signed out, or `/me` still in flight: no reader. The control must fail closed rather than flash
  // for everybody and then vanish.
  expect(canRunDesignWorkshops(null)).toBe(false);
});

test("an empty answer is an ordinary result, not a crash", () => {
  // A card that could not be read, a server that sent nothing, and a body that arrived without the
  // arrays at all must all take the same quiet path — the caller distinguishes them by
  // `rejectedAadhaarCount`, which is a COUNT and never the rejected values.
  expect(identityChoices({}, "ANY", aadhaarValidationError)).toEqual([]);
  expect(identityChoices({ aadhaarCandidates: null, pehchanCandidates: null }, "ANY", aadhaarValidationError)).toEqual([]);
  expect(identityChoices({ aadhaarCandidates: [{ value: null }] }, "AADHAAR", aadhaarValidationError)).toEqual([]);
});

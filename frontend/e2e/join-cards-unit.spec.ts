import { expect, test } from "@playwright/test";

import { ApiError } from "@/lib/api";
import {
  JOIN_CARD_REDEEM_OFFLINE_MESSAGE,
  decodeJoinCard,
  joinCardFailure,
  joinCardState,
  joinOutcomeIsMembership,
  joinOutcomeSentence,
  looksLikeJoinCard,
  type JoinCard
} from "@/lib/joinCards";
import { decodeWorkshopCode, formatWorkshopCodeForPrint, workshopCodeCheck } from "@/lib/workshopCodes";

/**
 * JOIN CARDS ON THE WEB — the decoder that must agree with the server and the handset on the canonical
 * string, and the readings of the server's answers that must never paint a provisional foothold as
 * membership. The vector is the handset's (`DwJoinCardTest.CARD`), so the two ports are held to one
 * card; the server recomputes the same check over the same canonical form.
 */

const WORKSHOP_ID = "cmsik2jg8000eh8xc1lcy661a";
const SECRET = "9TQ4V0KZ7BXMHR3NDPJ2WY";
const CARD = "DPW2:J:CMSIK2JG8000EH8XC1LCY661A.9TQ4V0KZ7BXMHR3NDPJ2WY:7AWF";

test.describe("decoding a join card", () => {
  test("a genuine card decodes to its workshop and its canonical string", () => {
    expect(workshopCodeCheck(CARD.slice(0, CARD.lastIndexOf(":")))).toBe("7AWF");
    expect(decodeJoinCard(CARD)).toEqual({ ok: true, workshopId: WORKSHOP_ID, code: CARD });
  });

  test("what a person does to it on the way in is forgiven: case, the print grouping, look-alike letters", () => {
    expect(decodeJoinCard(formatWorkshopCodeForPrint(CARD))).toEqual({ ok: true, workshopId: WORKSHOP_ID, code: CARD });
    expect(decodeJoinCard(CARD.toLowerCase())).toEqual({ ok: true, workshopId: WORKSHOP_ID, code: CARD });
    // `O` for `0` in the SECRET is folded back; the id is never folded.
    const misread = CARD.replace(SECRET, SECRET.replace("0", "O"));
    expect(decodeJoinCard(misread)).toEqual({ ok: true, workshopId: WORKSHOP_ID, code: CARD });
  });

  test("a genuine card is never answered by the record parser's 'update the app'", () => {
    const asRecord = decodeWorkshopCode(CARD);
    expect(asRecord.ok).toBe(false);
    expect(looksLikeJoinCard(CARD)).toBe(true);
    expect(looksLikeJoinCard(formatWorkshopCodeForPrint(CARD))).toBe(true);
    // The workshop's own tag is a record code and stays with the record parser.
    expect(looksLikeJoinCard("DPW1:G:CMSIK2JG8000EH8XC1LCY661A:0PK3")).toBe(false);
  });

  test("each damaged shape is refused with a sentence that never repeats the card", () => {
    const cases = [
      "",
      "https://example.com",
      "DPW1:G:CMSIK2JG8000EH8XC1LCY661A:0PK3",
      "DPW3:J:CMSIK2JG8000EH8XC1LCY661A.9TQ4V0KZ7BXMHR3NDPJ2WY:7AWF",
      "DPW2:J:CMSIK2JG8000EH8XC1LCY661A:7AWF",
      "DPW2:J:CMSIK2JG8000EH8XC1LCY661A.9TQ4V0KZ7BXMHR3NDPJ2W:7AWF",
      "DPW2:J:DWLOCAL-3F2504E04F8911D39A0C0305.9TQ4V0KZ7BXMHR3NDPJ2WY:XXXX",
      CARD.slice(0, -1) + "G"
    ];
    for (const input of cases) {
      const decoded = decodeJoinCard(input);
      expect(decoded.ok, input).toBe(false);
      if (!decoded.ok) {
        expect(decoded.message.length).toBeGreaterThan(10);
        expect(decoded.message).not.toContain(SECRET);
        expect(decoded.message.toLowerCase()).not.toMatch(/server|endpoint|http|not yet|coming soon/);
      }
    }
    expect((decodeJoinCard("DPW3:J:CMSIK2JG8000EH8XC1LCY661A.9TQ4V0KZ7BXMHR3NDPJ2WY:7AWF") as { message: string }).message).toContain(
      "newer format"
    );
    expect((decodeJoinCard("DPW2:J:DWLOCAL-3F2504E04F8911D39A0C0305.9TQ4V0KZ7BXMHR3NDPJ2WY:XXXX") as { message: string }).message).toContain(
      "had not been shared yet"
    );
    expect((decodeJoinCard(CARD.slice(0, -1) + "G") as { message: string }).message).toContain("does not check out");
  });
});

test.describe("reading the server's answers", () => {
  test("only FULL and ALREADY_A_MEMBER are membership; PROVISIONAL and unknown words are not", () => {
    expect(joinOutcomeIsMembership("FULL")).toBe(true);
    expect(joinOutcomeIsMembership("ALREADY_A_MEMBER")).toBe(true);
    expect(joinOutcomeIsMembership("PROVISIONAL")).toBe(false);
    expect(joinOutcomeIsMembership("SOMETHING_NEW")).toBe(false);
    expect(joinOutcomeIsMembership(undefined)).toBe(false);
  });

  test("the server's sentence is shown as given, and a missing one falls back per outcome", () => {
    expect(joinOutcomeSentence({ outcome: "PROVISIONAL", detail: "The server's own words." })).toBe("The server's own words.");
    expect(joinOutcomeSentence({ outcome: "PROVISIONAL" })).toContain("You are not on the workshop yet");
    expect(joinOutcomeSentence({ outcome: "FULL" })).toContain("You are on this workshop");
  });

  test("a refusal keeps its sentence; a dead network says nothing was spent", () => {
    const refused = joinCardFailure(new ApiError(403, "This join card cannot be used.", { detail: "This join card cannot be used." }));
    expect(refused).toEqual({ kind: "refused", message: "This join card cannot be used." });
    expect(joinCardFailure(new TypeError("Failed to fetch"), JOIN_CARD_REDEEM_OFFLINE_MESSAGE)).toEqual({
      kind: "offline",
      message: JOIN_CARD_REDEEM_OFFLINE_MESSAGE
    });
    expect(JOIN_CARD_REDEEM_OFFLINE_MESSAGE).toContain("has not been spent");
    expect(joinCardFailure(new ApiError(500, "boom", { detail: "boom" })).message).toContain("Try again in a moment");
  });
});

test.describe("a card's state, in the handset's order", () => {
  const NOW = Date.parse("2026-08-24T00:00:00Z");
  const base: JoinCard = {
    id: "t1",
    recordType: "DESIGN_WORKSHOP",
    recordId: WORKSHOP_ID,
    secretLast4: "J2WY",
    maxUses: 1,
    usesConsumed: 0,
    expiresAt: "2026-09-01T00:00:00Z",
    revokedAt: null,
    label: null,
    createdAt: "2026-08-20T00:00:00Z",
    issuedBy: { id: "u1", name: "Asha", email: "" }
  };

  test("cancelled beats used up, used up beats the date, and a good card says for how many", () => {
    expect(joinCardState({ ...base, revokedAt: "2026-08-22T00:00:00Z", usesConsumed: 1, expiresAt: "2026-08-01T00:00:00Z" }, NOW)).toMatch(
      /^Cancelled\./
    );
    expect(joinCardState({ ...base, usesConsumed: 1, expiresAt: "2026-08-01T00:00:00Z" }, NOW)).toMatch(/^Used up\./);
    expect(joinCardState({ ...base, expiresAt: "2026-08-01T00:00:00Z" }, NOW)).toMatch(/^Out of date\./);
    expect(joinCardState(base, NOW)).toBe("Still good for 1 more person. Printed by Asha.");
    expect(joinCardState({ ...base, maxUses: 5, usesConsumed: 2 }, NOW)).toContain("3 more people");
    expect(joinCardState({ ...base, maxUses: null }, NOW)).toContain("any number of people");
  });

  test("an unreadable date is not reported as expired", () => {
    expect(joinCardState({ ...base, expiresAt: null }, NOW)).toMatch(/^Still good/);
  });
});

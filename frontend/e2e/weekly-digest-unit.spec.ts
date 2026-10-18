import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import {
  DIGEST_PREFERENCE_PATH,
  digestCardView,
  digestHref,
  digestPath,
  digestTiles,
  optimistic,
  readPreference,
  sectionRows,
  toggleDigest,
  weekTitle,
  type Digest,
  type DigestPreference
} from "@/lib/weeklyDigest";

/**
 * THE MONDAY SUMMARY — the shared routes, the Settings card's decisions and the page's tiles. The
 * routes are pinned because the documentation portal and both handsets call the same three.
 */

const ELIGIBLE: DigestPreference = {
  eligible: true,
  enabled: true,
  emailAvailable: true,
  schedule: "Mondays, 08:00 IST",
  latest: null
};

test("the routes are the shared contract", () => {
  expect(DIGEST_PREFERENCE_PATH).toBe("/notifications/digest");
  expect(digestPath("c1")).toBe("/notifications/digests/c1");
  expect(digestHref("c1")).toBe("/digest/c1");
});

test("the card draws nothing for an account the summary is not for", () => {
  expect(digestCardView(null)).toBeNull();
  expect(digestCardView(readPreference({ eligible: false, emailAvailable: true }))).toBeNull();
  // A malformed answer is read as not eligible, never as a switch that cannot work.
  expect(digestCardView(readPreference(undefined))).toBeNull();
});

test("the channel line follows whether mail is on", () => {
  expect(digestCardView(ELIGIBLE)?.channel).toBe("By e-mail and here.");
  expect(digestCardView({ ...ELIGIBLE, emailAvailable: false })?.channel).toBe("Here, in your notifications.");
  expect(digestCardView(ELIGIBLE)?.description).toBe(
    "Mondays at 08:00: what waits for you, what is overdue, what came in."
  );
  expect(digestCardView(ELIGIBLE)?.latestHref).toBeNull();
  const withLatest = {
    ...ELIGIBLE,
    latest: { id: "d9", weekStart: "2026-10-19", waiting: 1, overdue: 0, newThisWeek: 0, coverage: null }
  };
  expect(digestCardView(withLatest)?.latestHref).toBe("/digest/d9");
});

test("the switch reverts when the save fails", async () => {
  const shown = optimistic(ELIGIBLE, false);
  expect(shown.enabled).toBe(false);
  const failed = await toggleDigest(ELIGIBLE, false, async () => {
    throw new Error("Network down");
  });
  expect(failed.state.enabled).toBe(true);
  expect(failed.error).toBe("Network down");
  const saved = await toggleDigest(ELIGIBLE, false, async (enabled) => ({ ...ELIGIBLE, enabled }));
  expect(saved).toEqual({ state: { ...ELIGIBLE, enabled: false }, error: null });
});

const DIGEST: Digest = {
  id: "d1",
  weekStart: "2026-10-19",
  generatedAt: "2026-10-19T02:30:00+00:00",
  sections: [
    {
      key: "waiting",
      label: "Waiting for you",
      total: 4,
      lines: [
        { key: "approvals", label: "Reports to approve", count: 3, link: "/design-workshop-approvals" },
        { key: "toHandOn", label: "Approved, to hand on", count: 0, link: "/design-workshop-approvals?state=approved" },
        { key: "evil", label: "Off site", count: 1, link: "https://example.org" }
      ],
      items: Array.from({ length: 7 }, (_, n) => ({ title: `W${n}`, link: `/design-workshops/w${n}`, since: null }))
    },
    { key: "overdue", label: "Overdue", total: 2, lines: [], items: [] },
    { key: "new", label: "New this week", total: 11, lines: [] }
  ],
  coverage: { label: "Reports in", value: 41, of: 58, percent: 71, scope: "Every workshop" }
};

test("the page draws four tiles in order", () => {
  const tiles = digestTiles(DIGEST);
  expect(tiles.map((tile) => [tile.label, tile.figure])).toEqual([
    ["Waiting", "4"],
    ["Overdue", "2"],
    ["New", "11"],
    ["Reports in", "71%"]
  ]);
  expect(tiles[3].note).toBe("41 of 58");
  // A coverage with no denominator (the documentation portal's districts) is a plain number.
  const districts = digestTiles({ ...DIGEST, coverage: { label: "Districts documented", value: 12, of: null, percent: null } });
  expect(districts[3]).toEqual({ key: "coverage", label: "Districts documented", figure: "12", note: null });
});

test("lines with a zero count and off-site links are hidden; at most five items", () => {
  const rows = sectionRows(DIGEST.sections[0]);
  expect(rows.lines.map((line) => line.key)).toEqual(["approvals"]);
  expect(rows.items).toHaveLength(5);
  expect(sectionRows(DIGEST.sections[2]).items).toEqual([]);
});

test("the title is the Monday, read as a calendar date", () => {
  expect(weekTitle("2026-10-19")).toBe("Week of 19 Oct");
  expect(weekTitle("2027-01-04")).toBe("Week of 4 Jan");
  expect(weekTitle("nonsense")).toBe("Weekly summary");
});

test("the card's switch is busy while saving and does not repeat the heading", () => {
  const card = readFileSync(join(__dirname, "..", "components/settings/WeeklyDigestCard.tsx"), "utf8");
  expect(card).toContain("busy={saving}");
  expect(card).toContain("label={DIGEST_SWITCH}");
});

import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import {
  AWAITING_INSPECTION_BADGE_HREF,
  MINISTRY_DESK,
  awaitingInspectionSentence,
  ministryDeskFor
} from "@/components/dashboard/ministryDesk";
import { splitAddresses } from "@/components/settings/ReportDeliverySettingsCard";
import { EMAIL_SWITCHES } from "@/components/settings/EmailNotificationsCard";
import { deliveryLines, deliveryOffer, type DwApprovalDetail } from "@/lib/designWorkshopApprovals";
import { receivedWords } from "@/lib/ministryDashboard";
import { HAND_IN_MODES, readNotificationPreferences } from "@/lib/notifications";
import { linkEmailWords, reminderSentence } from "@/lib/sanctionOrders";
import type { User, UserRole } from "@/lib/types";

/*
 * The 2026-10-18 notices and delivery on the web: the inspection badge's row and sentence, the
 * e-mail choices read off an older server, the sanction register's e-mail words, "Approve and hand
 * on" offered only where the server allows the decision, and "Received" on the ministry register.
 */

const user = (role: UserRole) => ({ id: `u-${role}`, role, name: role, email: `${role}@x.org` }) as unknown as User;

function detail(over: Partial<DwApprovalDetail>): DwApprovalDetail {
  return { id: "w1", status: "PRE_SUBMISSION", mayDecide: { approve: true }, ...over } as unknown as DwApprovalDetail;
}

test("the inspection badge rides 'Workshops to inspect', before the sign-off, for inspection holders only", () => {
  const hrefs = MINISTRY_DESK.map((row) => row.href);
  expect(hrefs.indexOf(AWAITING_INSPECTION_BADGE_HREF)).toBe(hrefs.length - 2);
  expect(ministryDeskFor(user("MINISTRY_ADMIN")).map((row) => row.href)).toContain(AWAITING_INSPECTION_BADGE_HREF);
  expect(ministryDeskFor(user("REGIONAL_DIRECTOR")).map((row) => row.href)).not.toContain(AWAITING_INSPECTION_BADGE_HREF);
  expect(awaitingInspectionSentence(1)).toBe("1 report is waiting for your review");
  expect(awaitingInspectionSentence(3)).toBe("3 reports are waiting for your review");
  const nav = readFileSync(join(__dirname, "..", "components", "DynamicIslandNav.tsx"), "utf8");
  expect(nav).toContain("<AwaitingInspectionBadge count={awaitingInspectionCount} />");
});

test("an older server's e-mail answer reads as everything on, and the hand-in words are three", () => {
  expect(readNotificationPreferences({ available: true, emailReviewNotes: false })).toEqual({
    available: true,
    emailReviewNotes: false,
    emailWorkshopUpdates: true,
    emailAccessNotices: true,
    emailHandInNotices: "EACH",
    reviewsHandIns: false
  });
  expect(readNotificationPreferences({ emailHandInNotices: "HOURLY" as never }).emailHandInNotices).toBe("EACH");
  expect(HAND_IN_MODES.map((mode) => mode.label)).toEqual(["Each time", "Once a day", "Never"]);
  expect(EMAIL_SWITCHES.map((item) => item.key)).toEqual(["emailReviewNotes", "emailWorkshopUpdates", "emailAccessNotices"]);
});

test("the sanction register says whether each sign-in e-mail went, and what a reminder did", () => {
  expect(linkEmailWords(null)).toBeNull();
  expect(linkEmailWords({ id: "m", status: "SENT", at: null, error: null })).toBe("Sign-in e-mail sent");
  expect(linkEmailWords({ id: "m", status: "FAILED", at: null, error: "x" })).toBe("Sign-in e-mail failed");
  expect(linkEmailWords({ id: "m", status: "QUEUED", at: null, error: null })).toBe("Sign-in e-mail sending");
  expect(
    reminderSentence({ queued: 2, recent: 1, optedOut: 0, noAddress: 0, lastRemindedAt: null, missing: ["Venue"] })
  ).toBe("Reminder sent to 2 designers. 1 was reminded in the last hour.");
  expect(reminderSentence({ queued: 0, recent: 0, optedOut: 0, noAddress: 0, lastRemindedAt: null, missing: [] })).toBe(
    "No reminder was sent."
  );
});

test("'Approve and hand on' is offered only where the server allows the decision", () => {
  const configured = { configured: true, addressCount: 1, officeName: "DC (H)", linkDays: 7, latest: null };
  expect(deliveryOffer(detail({ delivery: configured }))).toEqual({ kind: "send", label: "Approve and hand on" });
  expect(deliveryOffer(detail({ delivery: { ...configured, configured: false } })).kind).toBe("setup");
  expect(
    deliveryOffer(detail({ status: "APPROVED", mayDecide: { handOn: true }, delivery: configured }))
  ).toEqual({ kind: "send", label: "Hand on by e-mail" });
  expect(deliveryOffer(detail({ mayDecide: {}, delivery: configured })).kind).toBe("none");
  expect(deliveryOffer(detail({ decisionRefusal: "You inspect it.", delivery: configured })).kind).toBe("none");
  expect(deliveryOffer(detail({ status: "SUBMITTED", delivery: configured })).kind).toBe("none");
});

test("the delivery and the register say when it went and whether it was opened", () => {
  const latest = {
    id: "d1",
    sentAt: "2026-10-18T05:00:00Z",
    expiresAt: "2026-10-25T05:00:00Z",
    state: "LIVE" as const,
    officeName: "DC (H)",
    fileName: "r.pdf",
    firstOpenedAt: null,
    lastOpenedAt: null,
    openCount: 0,
    emails: []
  };
  const lines = deliveryLines({ configured: true, addressCount: 1, officeName: "DC (H)", linkDays: 7, latest }, (iso) => iso.slice(0, 10));
  expect(lines).toEqual(["E-mailed to DC (H) on 2026-10-18.", "The link works until 2026-10-25.", "Not opened yet."]);
  expect(receivedWords(null, (iso) => iso)).toBeNull();
  expect(receivedWords({ sentAt: "s", firstOpenedAt: "2026-10-19", officeName: "DC (H)" }, (iso) => iso)).toBe(
    "Received by DC (H) · opened 2026-10-19"
  );
});

test("office addresses split on commas, semicolons and new lines", () => {
  expect(splitAddresses("A@x.org, b@x.org;\nc@x.org  ")).toEqual(["a@x.org", "b@x.org", "c@x.org"]);
});

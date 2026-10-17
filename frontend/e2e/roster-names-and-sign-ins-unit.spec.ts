import { expect, test } from "@playwright/test";

import { lastSignIn } from "@/app/(protected)/users/accountAdmin";
import { accessEntryDetails, type AccessRosterEntry } from "@/lib/accessRoster";
import type { User } from "@/lib/types";

/**
 * Who added and decided an access-roster entry, in words, and when an account last signed in
 * (2026-10-17). The server joins the names; these pin what the two admin screens say with them.
 */

function entry(extra: Partial<AccessRosterEntry> = {}): AccessRosterEntry {
  return {
    id: "ar-1",
    email: "asha@example.org",
    fullName: "Asha",
    status: "ACTIVE",
    admitRole: null,
    joinedAt: null,
    requestedAt: null,
    attemptCount: 0,
    lastAttemptAt: null,
    decidedAt: null,
    decidedById: "u-9",
    firstSeenAt: null,
    notes: null,
    createdAt: "2026-10-01T09:00:00.000Z",
    updatedAt: null,
    addedById: "u-1",
    addedByName: "Meera Rao",
    decidedByName: "admin@example.org",
    ...extra
  } as AccessRosterEntry;
}

function row(details: [string, string | null][], label: string): string | null | undefined {
  return details.find(([name]) => name === label)?.[1];
}

test("an entry names who added it and who decided it, never an id", () => {
  const details = accessEntryDetails(entry());
  expect(row(details, "Added by")).toBe("Meera Rao");
  expect(row(details, "Decided by")).toBe("admin@example.org");
  for (const [, value] of details) expect(value ?? "").not.toContain("u-");
});

test("a request nobody has decided has no Decided by row", () => {
  const details = accessEntryDetails(entry({ status: "PENDING", decidedByName: null }));
  expect(details.map(([name]) => name)).not.toContain("Decided by");
  expect(row(details, "Added by")).toBe("Meera Rao");
});

test("an account that is gone leaves the row empty rather than inventing a name", () => {
  const details = accessEntryDetails(entry({ addedByName: null }));
  expect(row(details, "Added by")).toBeNull();
});

test("the users screen says the last sign-in, falling back to the first", () => {
  const base = { id: "u-1", email: "a@example.org", name: "A", role: "DESIGNER" } as User;
  expect(lastSignIn({ ...base, firstLoginAt: "2026-01-01T00:00:00Z", lastLoginAt: "2026-10-17T09:00:00Z" })).toEqual({
    label: "Last signed in",
    at: "2026-10-17T09:00:00Z"
  });
  expect(lastSignIn({ ...base, firstLoginAt: "2026-01-01T00:00:00Z", lastLoginAt: null })).toEqual({
    label: "First signed in",
    at: "2026-01-01T00:00:00Z"
  });
  expect(lastSignIn({ ...base, firstLoginAt: null })).toBeNull();
});

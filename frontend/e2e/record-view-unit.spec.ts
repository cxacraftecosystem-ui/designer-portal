import { existsSync, readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import { parseListMemory } from "@/components/hooks/useListMemory";
import { artisanFields, plainWords, rows, toolFields } from "@/components/records/recordFields";
import {
  canUpdateRecord,
  RECORD_UPDATE_HREF,
  RECORD_VIEW_HREF,
  recordLoadFailure,
  recordUpdateHref,
  reviewRecordViewHref,
  rowClickIntent,
  ROW_OWN_CLICK_SELECTOR,
  type ViewableRecord
} from "@/lib/recordView";
import type { Artisan, ToolDocumentation, User, UserRole } from "@/lib/types";

/**
 * A RECORD OPENS READ-ONLY FROM ANYWHERE ON ITS CARD; EDITING IS THE SEPARATE "UPDATE" — the owner's
 * ruling of 2026-10-16, on both clients.
 *
 * What this file holds the web to, in the order a reader meets it:
 *
 *   1. Where a record opens and where Update goes are two tables, both carrying the id
 *      (`lib/recordView.ts`). A bare list route in the Update table is the blank-CREATE-form bug
 *      §13.0 of the frontend contract records shipping in six places.
 *   2. Update is drawn only where the server's PATCH gate would let the save land, and never on a
 *      file, which has no form.
 *   3. A press on a row's own control (Update, Discuss, Delete, a preview tile) is that control's,
 *      not the row's; a drag that selected text, or a press that began outside the row, opens
 *      nothing; a middle or modified click asks for a new tab, as it does on the row's real link.
 *   4. The read-only page never prints an identity number — the list hides the digits, so the page
 *      a click on the list opens must too.
 *   5. Every list that draws records routes its rows through `OpenableRow`/`OpenableCard` to the
 *      read-only page, and no row action on those lists still says "Edit".
 */

const ROOT = join(__dirname, "..");
const read = (path: string) => readFileSync(join(ROOT, path), "utf8");

function account(role: UserRole): User {
  return { id: `u-${role}`, email: `${role.toLowerCase()}@example.org`, name: role, role } as User;
}

const KINDS: ViewableRecord[] = ["artisan", "product", "tool", "process", "craft", "workshop", "interview", "media"];

test.describe("where a record opens, and where Update goes", () => {
  test("every kind has a read-only page that carries the id", () => {
    for (const kind of KINDS) {
      const href = RECORD_VIEW_HREF[kind]("rec_123");
      expect(href, kind).toContain("rec_123");
      expect(href, `${kind} opens its edit form instead of its view`).not.toMatch(/\/edit\b|[?&]edit=/);
    }
  });

  test("the view pages exist where the table says", () => {
    const routes: Record<ViewableRecord, string> = {
      artisan: "app/(protected)/artisans/[id]/page.tsx",
      product: "app/(protected)/products/[id]/page.tsx",
      tool: "app/(protected)/tools/[id]/page.tsx",
      process: "app/(protected)/processes/[id]/page.tsx",
      craft: "app/(protected)/crafts/[id]/page.tsx",
      workshop: "app/(protected)/workshops/[id]/page.tsx",
      // An interview is a session now: its read view is the shared questionnaire's (UNIFY 4.4).
      interview: "app/(protected)/questionnaire/sessions/[id]/view/page.tsx",
      media: "app/(protected)/media/[id]/page.tsx"
    };
    for (const kind of KINDS) {
      expect(existsSync(join(ROOT, routes[kind])), `${routes[kind]} is missing`).toBe(true);
      const source = read(routes[kind]);
      if (kind === "interview") {
        expect(source, "a session's read view is the shared questionnaire's").toContain("<SessionReadView");
        continue;
      }
      expect(source, `${kind}'s page is not the shared read-only shell`).toContain("<RecordViewPage");
      expect(source).toContain(`kind="${kind}"`);
    }
  });

  test("Update keeps the id, in the shape each kind's form reads it", () => {
    expect(RECORD_UPDATE_HREF.artisan("a1")).toBe("/artisans/a1/edit");
    expect(RECORD_UPDATE_HREF.product("p1")).toBe("/products/p1/edit");
    expect(RECORD_UPDATE_HREF.tool("t1")).toBe("/tools/t1/edit");
    // The inline-form shape: the id travels as ?edit=, read by useEditDeepLink on the list page.
    expect(RECORD_UPDATE_HREF.process("x1")).toBe("/processes?edit=x1");
    expect(RECORD_UPDATE_HREF.craft("c1")).toBe("/crafts?edit=c1");
    expect(RECORD_UPDATE_HREF.workshop("w1")).toBe("/workshops?edit=w1");
    // An interview is a questionnaire session (UNIFY 4.4): Update opens its workspace.
    expect(RECORD_UPDATE_HREF.interview("i1")).toBe("/questionnaire/sessions/i1");
    // A stored file has no form, so it has no Update at all.
    expect(recordUpdateHref("media", "m1")).toBeNull();
  });

  test("ids are encoded, so a path segment cannot be smuggled through one", () => {
    expect(RECORD_VIEW_HREF.artisan("a/b")).toBe("/artisans/a%2Fb");
    expect(RECORD_UPDATE_HREF.craft("a&new=1")).toBe("/crafts?edit=a%26new%3D1");
  });
});

test.describe("Update is drawn only for a reader whose save would land", () => {
  test("signed out sees no Update anywhere", () => {
    for (const kind of KINDS) expect(canUpdateRecord(null, kind), kind).toBe(false);
  });

  test("a file never offers Update, whatever the role", () => {
    expect(canUpdateRecord(account("MASTER_ADMIN"), "media")).toBe(false);
  });

  test("crafts and workshops follow require_craft_manager / require_workshop_manager — Professor and above", () => {
    for (const kind of ["craft", "workshop"] as const) {
      expect(canUpdateRecord(account("RESEARCHER"), kind), kind).toBe(false);
      expect(canUpdateRecord(account("DESIGNER"), kind), kind).toBe(false);
      expect(canUpdateRecord(account("PROFESSOR"), kind), kind).toBe(true);
      expect(canUpdateRecord(account("ADMIN"), kind), kind).toBe(true);
    }
  });

  test("the field records and interviews keep their PATCH gate of a plain sign-in", () => {
    // guard_record_edit decides field by field on the server; the button was always offered to
    // every signed-in reader on these lists, and the ruling changes the label, not the gate.
    for (const kind of ["artisan", "product", "tool", "process", "interview"] as const) {
      expect(canUpdateRecord(account("CROWDSOURCE_VOLUNTEER"), kind), kind).toBe(true);
    }
  });

  test("the shell draws Update only behind canUpdateRecord", () => {
    const shell = read("components/records/RecordView.tsx");
    expect(shell).toContain("const mayUpdate = Boolean(updateHref) && canUpdateRecord(user, kind);");
    expect(shell).toContain("{mayUpdate && updateHref ? (");
    // The label is "Update", never "Edit".
    expect(shell).toMatch(/Update\s*<\/Link>/);
    expect(shell).not.toMatch(/>\s*Edit\s*</);
  });
});

test.describe("a press on a row", () => {
  const base = { button: 0, insideRow: true, onOwnControl: false };

  test("opens the record on a plain primary click", () => {
    expect(rowClickIntent(base)).toBe("open");
  });

  test("belongs to the control it landed on", () => {
    expect(rowClickIntent({ ...base, onOwnControl: true })).toBe("ignore");
  });

  test("is ignored when it started outside the row — a portalled menu raised from it", () => {
    expect(rowClickIntent({ ...base, insideRow: false })).toBe("ignore");
  });

  test("is ignored when the reader was selecting text to copy", () => {
    expect(rowClickIntent({ ...base, selectedText: "Bagru" })).toBe("ignore");
    expect(rowClickIntent({ ...base, selectedText: "   " })).toBe("open");
  });

  test("asks for a new tab on a middle click or a modified click, as the real link does", () => {
    expect(rowClickIntent({ ...base, button: 1 })).toBe("open-new-tab");
    expect(rowClickIntent({ ...base, ctrlKey: true })).toBe("open-new-tab");
    expect(rowClickIntent({ ...base, metaKey: true })).toBe("open-new-tab");
    expect(rowClickIntent({ ...base, shiftKey: true })).toBe("open-new-tab");
    expect(rowClickIntent({ ...base, button: 2 })).toBe("ignore");
  });

  test("the own-control selector covers every control a row carries", () => {
    for (const selector of ["a[href]", "button", "input", "select", "textarea", "label", "summary", "audio", "video", "[role='button']", "[data-row-ignore]"]) {
      expect(ROW_OWN_CLICK_SELECTOR, selector).toContain(selector);
    }
  });
});

test.describe("the read-only page prints what the list prints, and no more", () => {
  const artisan = {
    id: "a1",
    name: "Kamla Devi",
    place: "Bagru",
    status: "APPROVED",
    aadhaarNumber: "234123412346",
    pehchanCardAvailable: true,
    pehchanCardNumber: "RJ-1234-5678",
    notes: "  ",
    createdAt: "2026-10-01T10:00:00.000Z"
  } as Artisan;

  test("never the Aadhaar or Pehchan digits — only that a card is on file", () => {
    const printed = artisanFields(artisan);
    const all = printed.map((row) => row.value).join(" | ");
    expect(all).not.toContain("234123412346");
    expect(all).not.toContain("2346");
    expect(all).not.toContain("RJ-1234-5678");
    expect(printed.find((row) => row.label === "Aadhaar number")?.value).toBe("On file");
    expect(printed.find((row) => row.label === "Pehchan card number")?.value).toBe("On file");
  });

  test("empty values are left out rather than printed as a dash", () => {
    const printed = artisanFields(artisan);
    expect(printed.map((row) => row.label)).not.toContain("Notes");
    expect(rows([["A", ""], ["B", null], ["C", undefined], ["D", false], ["E", 0]])).toEqual([
      { label: "D", value: "No" },
      { label: "E", value: "0" }
    ]);
  });

  test("stored codes read as words; Decimal strings read as numbers with their unit", () => {
    expect(plainWords("HIGH_DEMAND")).toBe("High demand");
    expect(plainWords("Hand forged")).toBe("Hand forged");
    const tool = { id: "t1", toolkitName: "Chhapa", lengthInches: "12.50", weight: "abc", maker: "SELF_MADE" } as unknown as ToolDocumentation;
    const printed = toolFields(tool);
    expect(printed.find((row) => row.label === "Length")?.value).toBe("12.5 in");
    expect(printed.map((row) => row.label)).not.toContain("Weight");
    expect(printed.find((row) => row.label === "Maker")?.value).toBe("Self made");
  });
});

test.describe("Back lands where the reader left the list", () => {
  test("a stored place is read back only in the shape it was written", () => {
    expect(parseListMemory(null)).toBeNull();
    expect(parseListMemory("not json")).toBeNull();
    expect(parseListMemory("[1,2]")).toBeNull();
    expect(
      parseListMemory(
        JSON.stringify({
          query: "bagru",
          applied: "bagru",
          page: 4,
          scrollY: 820,
          funnel: { workshopId: "w1", craftId: "", artisanId: "" }
        })
      )
    ).toEqual({ query: "bagru", applied: "bagru", page: 4, scrollY: 820, funnel: { workshopId: "w1", craftId: "", artisanId: "" } });
    // A page below 1, a fractional page, a negative scroll and a half-formed funnel are dropped.
    expect(parseListMemory(JSON.stringify({ page: 0, scrollY: -5, funnel: { workshopId: "w1" } }))).toEqual({});
    expect(parseListMemory(JSON.stringify({ page: 2.5 }))).toEqual({});
    // A page's other filters come back as strings only.
    expect(parseListMemory(JSON.stringify({ filters: { status: "SUBMITTED", kind: 3, on: "1" } }))).toEqual({
      filters: { status: "SUBMITTED", on: "1" }
    });
  });

  test("every list that opens a record page remembers its place, not only the six field-record lists", () => {
    for (const path of [
      "app/(protected)/media/page.tsx",
      "app/(protected)/design-workshops/page.tsx",
      "app/(protected)/questionnaires/page.tsx"
    ]) {
      expect(read(path), path).toContain("useListMemory(");
    }
  });
});

test.describe("every record list opens its rows read-only", () => {
  const LISTS: Array<[path: string, kind: ViewableRecord]> = [
    ["app/(protected)/artisans/page.tsx", "artisan"],
    ["app/(protected)/products/page.tsx", "product"],
    ["app/(protected)/tools/page.tsx", "tool"],
    ["app/(protected)/processes/page.tsx", "process"],
    ["app/(protected)/crafts/page.tsx", "craft"],
    ["app/(protected)/workshops/page.tsx", "workshop"],
    ["app/(protected)/media/page.tsx", "media"]
  ];

  for (const [path, kind] of LISTS) {
    test(`${path} — the whole row opens the ${kind}, and its action says Update`, () => {
      const source = read(path);
      expect(source).toContain(`<OpenableRow`);
      expect(source).toContain(`RECORD_VIEW_HREF.${kind}(`);
      expect(source, "the row's name must be the keyboard route — a real link").toContain("<OpenableRowLink");
      // No row action on these lists still says "Edit".
      expect(source).not.toMatch(/className=\{rowAction\("edit"\)\}[^>]*>\s*Edit\s*</);
      expect(source).not.toMatch(/>\s*Edit\s*<\/(Link|button)>/);
    });
  }

  test("the record lists that remember their place use useListMemory", () => {
    for (const path of LISTS.slice(0, 6).map(([file]) => file)) {
      expect(read(path), path).toContain("useListMemory(");
    }
  });

  test("the artisans row no longer selects — its launch cards moved to the artisan's page", () => {
    const list = read("app/(protected)/artisans/page.tsx");
    expect(list).not.toContain("setSelectedArtisan");
    expect(read("app/(protected)/artisans/[id]/page.tsx")).toContain("Make a tool entry");
  });

  test("the review queue opens the record it is reviewing, read-only, from anywhere on the row", () => {
    const review = read("app/(protected)/review/page.tsx");
    expect(review).toContain("<OpenableRow");
    expect(review).toContain("reviewRecordViewHref(item.recordType, item.id)");
    expect(review).toContain("<OpenableRowLink");
    expect(reviewRecordViewHref("questionnaire", "q1")).toBe("/questionnaire/sessions/q1/view");
    expect(reviewRecordViewHref("artisan", "a1")).toBe("/artisans/a1");
    expect(reviewRecordViewHref("somethingElse", "x")).toBeNull();
  });

  test("the folder browser's file cards open the stored file's page", () => {
    const data = read("app/(protected)/data/page.tsx");
    expect(data).toContain('<OpenableCard as="li"');
    expect(data).toContain("RECORD_VIEW_HREF.media(file.mediaId)");
  });

  test("sanction orders and the two rosters open a read-only view of the entry", () => {
    for (const path of [
      "app/(protected)/sanction-orders/page.tsx",
      "app/(protected)/admin/access/page.tsx",
      "app/(protected)/admin/designers/page.tsx"
    ]) {
      const source = read(path);
      expect(source, path).toContain("<RecordDetailsDialog");
      expect(source, path).toMatch(/<Openable(Row|Card)\b[^>]*onOpen=\{\(\) => setViewing\(/);
    }
  });

  test("a read-only page never offers the transcript re-run", () => {
    expect(read("components/media/TranscriptBlock.tsx")).toContain("!readOnly && isAdmin(user)");
    expect(read("components/media/ExistingMedia.tsx")).toContain("readOnly={readOnly}");
    expect(read("components/records/RecordView.tsx")).toContain("<TranscriptBlock media={media} readOnly />");
  });

  test("the forms reached through Update say Update", () => {
    for (const [path, title] of [
      ["app/(protected)/artisans/[id]/edit/page.tsx", 'title="Update artisan"'],
      ["app/(protected)/products/[id]/edit/page.tsx", 'title="Update product"'],
      ["app/(protected)/tools/[id]/edit/page.tsx", 'title="Update tool"'],
      ["app/(protected)/design-workshops/[id]/edit/page.tsx", 'title="Update workshop details"']
    ]) {
      expect(read(path), path).toContain(title);
    }
  });

  test("search hits, the data browser and recent submissions open the read-only page too", () => {
    expect(read("app/(protected)/search/page.tsx")).toContain("<OpenableCard");
    expect(read("components/data/DataSearchPanel.tsx")).toContain("<OpenableCard");
    const dashboard = read("app/(protected)/dashboard/page.tsx");
    expect(dashboard).toContain("RECORD_VIEW_HREF.artisan(id)");
    expect(dashboard).toContain("<OpenableRow");
    expect(read("components/map/types.ts")).toContain("artisans: RECORD_VIEW_HREF.artisan");
  });
});

test("a record that did not open says gone or not yours, never the server's bare words", () => {
  expect(recordLoadFailure("artisan", 404, "Record not found")).toBe("This artisan could not be found. It may have been deleted.");
  expect(recordLoadFailure("artisan", 403, "Forbidden")).toBe("Your account can't open this artisan.");
  // A 404 is about the record only: nothing in it suggests a permission the reader lacks.
  expect(recordLoadFailure("artisan", 404, null)).not.toMatch(/shared|access|permission/i);
  expect(recordLoadFailure("artisan", 500, "The server couldn't respond.")).toBe("The server couldn't respond.");
  expect(recordLoadFailure("artisan", null, "Record not found")).toBe("Unable to load this artisan.");
  expect(recordLoadFailure("artisan", null, "")).toBe("Unable to load this artisan.");
});

test("the artisan edit form explains a failed load the way the read-only page does", () => {
  const edit = readFileSync(join(ROOT, "app/(protected)/artisans/[id]/edit/page.tsx"), "utf8");
  const view = readFileSync(join(ROOT, "components/records/RecordView.tsx"), "utf8");
  for (const source of [edit, view]) {
    expect(source).toContain("recordLoadFailure(");
    expect(source).not.toMatch(/setError\(err instanceof Error \? err\.message/);
  }
});

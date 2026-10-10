import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import type { DwCustomDefinition } from "@/lib/customSections";
import {
  INSPECTION_ROUND_OPTIONS,
  INSPECTION_STATUS_OPTIONS,
  inspectableFilterCount,
  inspectableListQuery
} from "@/lib/designWorkshopInspections";
import { poolCountsLabel, poolFirstKind } from "@/lib/poolDirectory";
import {
  customAnswersForStage,
  mediaIndex,
  readableSize,
  readerMediaKind,
  readerMediaPath,
  resolveFieldMedia,
  type ReaderMediaFile
} from "@/lib/workshopReaderMedia";

/**
 * THE THREE GAPS THE SWEEP NAMED, CLOSED ON THE WEB — what can be decided without a browser.
 *
 * F5: an inspector and a directorate post holder see a workshop's files and the wording of its own
 * questions, read-only. F13: the inspection list's filters. F8: the pool directory on Design review.
 * The server half — who is admitted and refused, and that every link is signed — is
 * `backend/tests/test_reader_media_and_pool_directory.py`.
 */

const ROOT = join(__dirname, "..");
const read = (path: string) => readFileSync(join(ROOT, path), "utf8");

function file(id: string, mediaType: ReaderMediaFile["mediaType"], mimeType: string, url?: string): ReaderMediaFile {
  return { id, originalFilename: `${id}.bin`, mediaType, mimeType, url };
}

test.describe("F5 — a workshop's files, read-only", () => {
  test("each surface reads the files through its own prefix", () => {
    expect(readerMediaPath("inspection", "dw 1")).toBe("/design-workshop-inspections/dw%201/media");
    expect(readerMediaPath("oversight", "dw_1")).toBe("/design-workshop-oversight/assigned/dw_1/media");
  });

  test("a file is drawn by its stored type, then its MIME type", () => {
    expect(readerMediaKind({ mediaType: "IMAGE", mimeType: "image/jpeg" })).toBe("image");
    expect(readerMediaKind({ mediaType: "AUDIO", mimeType: "audio/mpeg" })).toBe("audio");
    expect(readerMediaKind({ mediaType: "VIDEO", mimeType: "video/mp4" })).toBe("video");
    expect(readerMediaKind({ mediaType: "OTHER", mimeType: "audio/ogg" })).toBe("audio");
    expect(readerMediaKind({ mediaType: "PDF", mimeType: "application/pdf" })).toBe("document");
    expect(readerMediaKind({ mediaType: "DOCUMENT", mimeType: "image/png" })).toBe("document");
  });

  test("a media field's ids resolve against the workshop's files, and the rest are counted", () => {
    const byId = mediaIndex({
      items: [file("m1", "IMAGE", "image/jpeg", "https://signed/1"), file("m2", "AUDIO", "audio/mpeg")],
      truncated: false
    });
    expect(resolveFieldMedia(["m2", "elsewhere", "m1"], byId)).toEqual({
      shown: [byId.get("m2"), byId.get("m1")],
      elsewhere: 1
    });
    expect(resolveFieldMedia("m1", byId).shown).toHaveLength(1);
    expect(resolveFieldMedia(undefined, byId)).toEqual({ shown: [], elsewhere: 0 });
    expect(mediaIndex(null).size).toBe(0);
  });

  test("a size reads the way a person reads one, and an unknown size says nothing", () => {
    expect(readableSize(512)).toBe("512 B");
    expect(readableSize("2048")).toBe("2 KB");
    expect(readableSize(5 * 1024 * 1024)).toBe("5.0 MB");
    expect(readableSize(null)).toBe("");
    expect(readableSize("not a number")).toBe("");
  });

  test("custom answers are printed beside their questions, retired ones kept, orphans counted", () => {
    const definition: DwCustomDefinition = {
      customSchemaVersion: "v1",
      fetchedAt: "2026-10-10T00:00:00Z",
      sections: [
        {
          id: "s1",
          key: "local",
          stageKey: "WORKSHOP_SETUP",
          title: "Local questions",
          description: "",
          sortOrder: 0,
          revision: 1,
          retired: false,
          fields: [
            {
              id: "f1",
              key: "mandi",
              label: "Which mandi buys the cloth?",
              type: "ENUM",
              tier: "STANDARD",
              required: false,
              help: "",
              unit: "",
              options: [{ value: "bargarh", label: "Bargarh" }],
              maxLength: 0,
              minValue: null,
              maxValue: null,
              sortOrder: 0,
              retired: false,
              supersededById: null
            },
            {
              id: "f2",
              key: "old",
              label: "Old wording",
              type: "TEXT",
              tier: "STANDARD",
              required: false,
              help: "",
              unit: "",
              options: [],
              maxLength: 0,
              minValue: null,
              maxValue: null,
              sortOrder: 1,
              retired: true,
              supersededById: null
            },
            {
              id: "f3",
              key: "blank",
              label: "Never answered",
              type: "TEXT",
              tier: "STANDARD",
              required: false,
              help: "",
              unit: "",
              options: [],
              maxLength: 0,
              minValue: null,
              maxValue: null,
              sortOrder: 2,
              retired: false,
              supersededById: null
            }
          ]
        }
      ]
    };
    const stamp = { by: "u1", byName: "Asha", at: "2026-03-01T09:00:00Z" };
    const { blocks, unmatched } = customAnswersForStage(
      definition,
      "WORKSHOP_SETUP",
      { mandi: "bargarh", old: "kept", gone: "an answer whose question was removed", blank: "" },
      { mandi: stamp }
    );
    expect(blocks).toHaveLength(1);
    expect(blocks[0].title).toBe("Local questions");
    expect(blocks[0].answers.map((answer) => [answer.field.key, answer.text])).toEqual([
      ["mandi", "Bargarh"],
      ["old", "kept"]
    ]);
    expect(blocks[0].answers[0].stamp).toEqual(stamp);
    expect(unmatched).toBe(1);
    expect(customAnswersForStage(definition, "ANOTHER_STAGE", { mandi: "bargarh" })).toEqual({
      blocks: [],
      unmatched: 1
    });
    expect(customAnswersForStage(null, "WORKSHOP_SETUP", undefined)).toEqual({ blocks: [], unmatched: 0 });
  });

  test("the files are drawn by a component that has no way to write", () => {
    const source = read("components/designworkshop/ReaderWorkshopMedia.tsx");
    expect(source).not.toMatch(/method:\s*["'](POST|PUT|PATCH|DELETE)/);
    expect(source).not.toMatch(/<input[^>]*type=["']file/);
    for (const page of [
      "app/(protected)/design-workshop-inspections/[id]/page.tsx",
      "app/(protected)/officers/monitored/[id]/page.tsx"
    ]) {
      const text = read(page);
      expect(text, page).toContain("ReaderMediaValue");
      expect(text, page).toContain("ReaderCustomAnswers");
      expect(text, page).not.toMatch(/read does not carry\s+photographs/);
      expect(text, page).not.toMatch(/route an (inspection|oversight read) does not reach/);
    }
  });
});

test.describe("F13 — the inspection list's filters", () => {
  test("blank filters are left out and set ones are sent", () => {
    expect(inspectableListQuery({ page: 1, pageSize: 20, search: "  ", statusFilter: "" })).toBe("?page=1&pageSize=20");
    expect(
      inspectableListQuery({
        statusFilter: "PRE_SUBMISSION",
        round: 0,
        state: "Odisha",
        workshopKind: "DESIGN_PROTOTYPE_DEVELOPMENT",
        dateFrom: "2026-03-01",
        dateTo: "2026-03-31"
      })
    ).toBe(
      "?statusFilter=PRE_SUBMISSION&round=0&state=Odisha&workshopKind=DESIGN_PROTOTYPE_DEVELOPMENT&dateFrom=2026-03-01&dateTo=2026-03-31"
    );
    expect(inspectableListQuery({ round: -1 })).toBe("");
    expect(inspectableListQuery({ round: 1.5 })).toBe("");
  });

  test("the filter count ignores the search box and counts round 0", () => {
    expect(inspectableFilterCount({ search: "ikat" })).toBe(0);
    expect(inspectableFilterCount({ round: 0, state: "Odisha", dateTo: "2026-01-01" })).toBe(3);
  });

  test("the status choices are the statuses a workshop can hold, any first", () => {
    expect(INSPECTION_STATUS_OPTIONS[0]).toEqual({ value: "", label: "Any status" });
    expect(INSPECTION_STATUS_OPTIONS.map((option) => option.value).slice(1).sort()).toEqual(
      ["APPROVED", "ARCHIVED", "COMPLETE", "DRAFT", "IN_PROGRESS", "NEEDS_REVISION", "PRE_SUBMISSION", "SUBMITTED"]
    );
    expect(INSPECTION_ROUND_OPTIONS.slice(0, 3).map((option) => option.value)).toEqual(["", "0", "1"]);
  });

  test("the walkthrough no longer says the list has no filters", () => {
    expect(read("components/guide/inspectorSteps.ts")).not.toContain("there is no filter by designer, district or date");
  });
});

test.describe("F8 — the pool directory", () => {
  test("a row says what is open in words, leaving out what is not", () => {
    expect(poolCountsLabel({ prototype: 2, sketch: 1 })).toBe("2 prototypes and 1 sketch open");
    expect(poolCountsLabel({ prototype: 0, sketch: 3 })).toBe("3 sketches open");
    expect(poolCountsLabel({})).toBe("Nothing open");
  });

  test("opening a row shows prototypes first unless only sketches are open", () => {
    expect(poolFirstKind({ prototype: 1, sketch: 4 })).toBe("prototype");
    expect(poolFirstKind({ prototype: 0, sketch: 4 })).toBe("sketch");
    expect(poolFirstKind({})).toBe("prototype");
  });

  test("Design review lists the pool and no longer says nothing lists it", () => {
    const page = read("app/(protected)/design-review/page.tsx");
    expect(page).toContain("listPoolWorkshops");
    expect(page).toContain("Workshops open to the pool");
    expect(page).not.toContain("What does not exist yet is a list of every");
    expect(page).not.toContain("and nothing lists those");
  });
});

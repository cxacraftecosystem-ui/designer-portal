import { expect, test } from "@playwright/test";

import { ApiError } from "@/lib/api";
import {
  clearedLinkKeys,
  danglingCandidates,
  danglingKeys,
  emptyPickerKeys,
  isDanglingReference,
  outboxDanglingSentence,
  outboxSentUnfiledMessage,
  referenceFieldNoun,
  repickEmptyLine,
  UNFILED_BY_CHOICE,
  UNFILED_NO_OPTIONS,
  unfiledLinkReason,
  workshopUnfiledReasons
} from "@/lib/offline";
import { narrowedTo, referenceCacheKey, referenceRecordIsReadable, REFERENCE_CACHE_VERSION } from "@/lib/referenceCache";
import { cachedListLine } from "@/lib/workshopOptions";

/**
 * THE OFFLINE REFERENCE CONTRACT'S PURE HALF, PINNED WHERE IT CAN ACTUALLY BE CHECKED.
 *
 * ── WHY THESE PARTICULAR FUNCTIONS GET A SPEC AND THE COMPONENTS DO NOT ────────────────────────
 *
 * Every judgement below is only ever REACHED in conditions nobody develops in: a laptop that has
 * never had signal, a laptop that had signal nine days ago, a workshop an administrator deleted at
 * the office between a courtyard save and the next drain. On a desk with a working connection the
 * cached branch, the offline branch and the dangling branch are all unreachable, so a sentence
 * chosen inside a component is a sentence only somebody standing in a village ever sees — which is
 * the same argument `components/ui/selectFilter.ts` and `components/data/cappedList.ts` make for
 * their own split, and the reason `DROPDOWN_DESIGN.md` §3.5 writes the strings as functions.
 *
 * There is no React renderer in devDependencies, so these call the real exports directly.
 */

/* ────────────────────────────────────────────────────────────────────────────
 * The cache key — `model__owner__filter`, mirroring `DwReferenceStore.cacheKey`
 * ──────────────────────────────────────────────────────────────────────────── */

test.describe("the register cache key", () => {
  test("the whole register and one narrowing are different documents", () => {
    // `unnamed` AND NOT `_`, AND THAT IS INHERITED ON PURPOSE. `DwReferenceStore.cacheKey` uses `_`
    // as the blank-filter placeholder and then passes every segment through a `safeName` that trims
    // `_` off both ends and falls back to `unnamed` — so the placeholder is eaten and the Kotlin key
    // is `artisan__ALL__unnamed` too. It is asserted here rather than "corrected" because a key that
    // differs between the two clients is a rule proved about one that has stopped being a rule about
    // the other; what the key has to be is stable and unambiguous, and it is both.
    expect(referenceCacheKey("artisan")).toBe("artisan__ALL__unnamed");
    expect(referenceCacheKey("artisan", "craft-7")).toBe("artisan__ALL__craft-7");
    // The two must never collide: a craft's roster is a different answer from the whole register,
    // and serving one for the other is how a picker offers artisans of the wrong craft.
    expect(referenceCacheKey("artisan")).not.toBe(referenceCacheKey("artisan", "craft-7"));
  });

  test("the owner segment is written out even though it is always ALL", () => {
    // Kept so this client's key and `DwReferenceStore`'s are the same shape. A rule proved about one
    // stops being a rule about the other the moment they differ by a field.
    for (const model of ["craft", "artisan", "product", "tool"] as const) {
      expect(referenceCacheKey(model).split("__")).toHaveLength(3);
      expect(referenceCacheKey(model).split("__")[1]).toBe("ALL");
    }
  });

  test("a segment can never end in an underscore, so a prefix cannot match half a segment", () => {
    // The Kotlin twin's `safeName` trims for exactly this reason, and its KDoc names the
    // cross-workshop leak that comes back if a segment is allowed to. Nothing reads by prefix on
    // this client today; the key is built to survive the day something does.
    const key = referenceCacheKey("artisan", "craft/7 ");
    expect(key).toBe("artisan__ALL__craft_7");
    for (const segment of key.split("__")) {
      expect(segment.startsWith("_")).toBe(false);
      expect(segment.endsWith("_")).toBe(false);
    }
  });

  test("a blank filter is spelled and does not collapse into the separator", () => {
    // `_` is the placeholder, so `model__ALL___` still parses back as three segments rather than
    // two with a trailing separator.
    expect(referenceCacheKey("tool", "   ")).toBe(referenceCacheKey("tool"));
  });
});

test.describe("narrowing a cached list on the device", () => {
  const rows = [
    { id: "p1", label: "Bandhani stole", hint: "Ram", filterValue: "a1" },
    { id: "p2", label: "Block-printed kurta", hint: "Sita", filterValue: "a2" },
    { id: "p3", label: "Unattributed motif", hint: "", filterValue: "" }
  ];

  test("a parent value narrows to its own children", () => {
    expect(narrowedTo(rows, "a1").map((row) => row.id)).toEqual(["p1", "p3"]);
  });

  test("AN OPTION CARRYING NO PARENT AT ALL IS KEPT, never dropped", () => {
    // A server that stops populating the cascade key would otherwise empty every cascading dropdown
    // in the app, which is a far worse failure than showing a few options too many. Same decision,
    // same words, as `DwReferenceList.narrowedTo`.
    expect(narrowedTo(rows, "a2").map((row) => row.id)).toContain("p3");
  });

  test("no parent value means the whole list", () => {
    expect(narrowedTo(rows, "")).toHaveLength(3);
    expect(narrowedTo(rows, "   ")).toHaveLength(3);
  });
});

test.describe("what may be served out of the register store", () => {
  const record = {
    key: referenceCacheKey("craft"),
    schemaVersion: REFERENCE_CACHE_VERSION,
    model: "craft" as const,
    filteredBy: "",
    fetchedAt: "2026-08-22T09:00:00.000Z",
    items: []
  };

  test("a record from a FUTURE build is refused rather than half-understood", () => {
    // A build that half-decodes a newer record renders a picker with rows silently missing, and a
    // picker missing rows is indistinguishable from a register that never had them.
    expect(referenceRecordIsReadable({ ...record, schemaVersion: REFERENCE_CACHE_VERSION + 1 })).toBe(false);
    expect(referenceRecordIsReadable(record)).toBe(true);
  });

  test("an empty document is READABLE — it is the server's answer, not an absence", () => {
    // Null means "this browser has never asked" (§3.5's empty-because-offline, whose next move is a
    // connection); an empty document means "the server said there are none" (genuinely-empty, whose
    // next move is to create one). Collapsing them is the bug this whole area is about.
    expect(referenceRecordIsReadable({ ...record, items: [] })).toBe(true);
    expect(referenceRecordIsReadable(null)).toBe(false);
  });
});

/* ────────────────────────────────────────────────────────────────────────────
 * §3.5's fifth sentence
 * ──────────────────────────────────────────────────────────────────────────── */

test("the cached-and-stale sentence is §3.5's, and it carries the date", () => {
  // Byte for byte the Kotlin `cachedListLine`. §3.5 says both clients print these word for word, and
  // a second wording of one fact is a second fact as far as a reader is concerned.
  expect(cachedListLine(42, "artisans", "22 Aug 2026")).toBe(
    "42 artisans on this device, last refreshed 22 Aug 2026. If the one you want is missing, " +
      "refresh with a connection before concluding it is not on record."
  );
  // The date is the whole sentence: without it a designer cannot tell "this person has no record"
  // from "this copy is nine days old", which is the judgement the cache exists to let them make.
  expect(cachedListLine(1, "crafts", "3 Mar 2026")).toContain("3 Mar 2026");
});

/* ────────────────────────────────────────────────────────────────────────────
 * R1's sign flipped for a form field — the unfile sentinel
 * ──────────────────────────────────────────────────────────────────────────── */

test.describe("which of the two absences an empty link box is in", () => {
  test("a box that HELD a workshop and does not now is a decision, whatever the list looks like", () => {
    // Only a person can produce this combination, and it has to be read as a decision even when the
    // list is empty right now: a record filed under a workshop this browser cannot list still draws
    // its recovered off-page row, so the "none" row stays reachable with the page empty.
    expect(unfiledLinkReason("", "dw-1", false)).toBe(UNFILED_BY_CHOICE);
    expect(unfiledLinkReason("", "dw-1", true)).toBe(UNFILED_BY_CHOICE);
  });

  test("never held one, but there was a list to pick from — also a decision", () => {
    expect(unfiledLinkReason("", "", true)).toBe(UNFILED_BY_CHOICE);
  });

  test("never held one and there was nothing to hold — NOT a decision", () => {
    // The drain says so when the record lands. Reading this as a choice is how a correction composed
    // on the bus home silently strips a link nobody was ever shown.
    expect(unfiledLinkReason("", "", false)).toBe(UNFILED_NO_OPTIONS);
  });

  test("a box holding something has no absence to explain", () => {
    expect(unfiledLinkReason("dw-2", "dw-1", true)).toBeNull();
  });
});

test.describe("what the replay does with that evidence", () => {
  test("only a DECISION becomes an explicit null on the wire", () => {
    const entry = {
      unfiled: workshopUnfiledReasons({ designWorkshop: UNFILED_BY_CHOICE, workshop: UNFILED_NO_OPTIONS })
    };
    expect(clearedLinkKeys(entry)).toEqual(["designWorkshopId"]);
    expect(emptyPickerKeys(entry)).toEqual(["workshopId"]);
  });

  test("a form that mounted only one picker cannot clear the other", () => {
    // Nothing is not a clearance: the column is absent from the map, absent from the replayed body,
    // and the stored value stands.
    expect(workshopUnfiledReasons({ designWorkshop: UNFILED_BY_CHOICE })).toEqual({
      designWorkshopId: UNFILED_BY_CHOICE
    });
  });

  test("an entry from before this existed clears nothing", () => {
    // The whole compatibility story: no evidence, no clearance, and the replay behaves exactly as it
    // did before the field existed.
    expect(clearedLinkKeys({})).toEqual([]);
    expect(emptyPickerKeys({})).toEqual([]);
  });
});

/* ────────────────────────────────────────────────────────────────────────────
 * R7's other half — a queued record pointing at an id the server does not have
 * ──────────────────────────────────────────────────────────────────────────── */

test.describe("recognising a dangling reference", () => {
  test("a 404 on a create or a correction is one", () => {
    expect(isDanglingReference(new ApiError(404, "Record not found", null))).toBe(true);
  });

  test("a 422 is one only when the server's own words say so", () => {
    // The alternative — every 422 — would offer a re-pick for a refusal about a missing product
    // name, sending the researcher to a dropdown that cannot fix it.
    expect(isDanglingReference(new ApiError(422, "Workshop not found", null))).toBe(true);
    expect(isDanglingReference(new ApiError(422, "productName must not be blank", null))).toBe(false);
  });

  test("a 409 and a plain Error are not", () => {
    // A 409 is a collision with somebody else's record and has its own arm; a bare Error is the
    // network, which the triage table owns.
    expect(isDanglingReference(new ApiError(409, "Aadhaar already recorded", null))).toBe(false);
    expect(isDanglingReference(new Error("Failed to fetch"))).toBe(false);
  });
});

test.describe("which id could be the missing one", () => {
  test("the candidates come back in REFERENCE_FIELD_NOUNS order", () => {
    // Order is load-bearing: two entries refused the same way must never word the same ambiguity two
    // different ways.
    const body = JSON.stringify({ name: "Ram", workshopId: "w1", designWorkshopId: "d1", artisanId: "a1" });
    expect(danglingCandidates(body)).toEqual(["designWorkshopId", "workshopId", "artisanId"]);
  });

  test("a body naming no reference has nothing to re-pick", () => {
    // Such an entry takes the ordinary refusal arm. A re-pick panel with no field in it would be the
    // dead end this whole arm exists to remove, wearing a new costume.
    expect(danglingCandidates(JSON.stringify({ name: "Ram" }))).toEqual([]);
    expect(danglingCandidates("not json")).toEqual([]);
  });

  test("a blank id is not a candidate", () => {
    expect(danglingCandidates(JSON.stringify({ workshopId: "", designWorkshopId: "d1" }))).toEqual([
      "designWorkshopId"
    ]);
  });

  test("the stored marker reads back as the list it is", () => {
    expect(danglingKeys({ danglingField: "designWorkshopId, workshopId" })).toEqual([
      "designWorkshopId",
      "workshopId"
    ]);
    expect(danglingKeys({})).toEqual([]);
  });
});

test.describe("what the researcher is told", () => {
  test("one candidate is named in the words of the box they will reopen", () => {
    const said = outboxDanglingSentence("Record not found", [referenceFieldNoun("designWorkshopId")], 0, false);
    expect(said).toContain("This record points at a design & prototype workshop that is not on the server.");
    // Nothing-is-lost comes BEFORE the server's words, because the control beside this sentence is
    // Discard and a person who has read "the server rejected this" reaches for it.
    expect(said.indexOf("Nothing is lost")).toBeLessThan(said.indexOf("The server said"));
    expect(said).toContain("Sending it again unchanged will get the same answer");
  });

  test("more than one candidate is an honest ambiguity, never a guess", () => {
    const said = outboxDanglingSentence("Record not found", ["design & prototype workshop", "workshop"], 2, true);
    expect(said).toContain("This correction points at something that is not on the server.");
    expect(said).toContain("the server's answer does not say which");
    expect(said).toContain("2 file(s)");
  });

  test("zero files omits the clause rather than printing a zero", () => {
    // "and 0 files saved with it" reads as an accusation that something went missing.
    expect(outboxDanglingSentence("Record not found", ["workshop"], 0, false)).not.toContain("0 file");
  });
});

test.describe("the re-pick panel with nothing to offer", () => {
  test("a failed read and an empty scope are different sentences", () => {
    // Their next moves are a connection and an administrator, and somebody sent to the wrong one
    // loses a day. This is the one surface whose whole job is to be a way out.
    expect(repickEmptyLine("workshop", false)).toContain("could not be read just now");
    expect(repickEmptyLine("workshop", true)).toContain("An administrator can give you access to one");
  });

  test("neither promises what §3.5 promises on a form", () => {
    // §3.5's sentences end "this record can be saved without it". Here the record IS saved and it is
    // stuck, so that promise would be true and useless; what is owed instead is the standing fact
    // that nothing goes away while this panel cannot help. Both arms say it, in their own words.
    for (const listed of [true, false]) {
      expect(repickEmptyLine("workshop", listed)).not.toContain("can be saved without it");
    }
    expect(repickEmptyLine("workshop", false)).toContain("Nothing has been lost");
    expect(repickEmptyLine("workshop", true)).toContain("nothing is deleted");
  });
});

test("a record that went up filed under nothing says so, and it is not a failure", () => {
  const said = outboxSentUnfiledMessage("Artisan · Giriraj Prasad", ["design & prototype workshop", "workshop"]);
  expect(said).toContain("was sent, and it is filed under nothing");
  // NO ARTICLES: "no" already carries the determiner.
  expect(said).toContain("there was no design & prototype workshop or workshop to choose from");
  expect(said).toContain("That was never a claim that none exist.");
  // It must not borrow the dangling sentence's remedy: nothing is refused and there is no button.
  expect(said).not.toContain("Re-pick");
});

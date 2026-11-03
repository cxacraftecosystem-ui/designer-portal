import { expect, test } from "@playwright/test";

import { ApiError } from "@/lib/api";
import {
  UNRECORDED,
  aiLayerProblem,
  flattenAiLayerNodes,
  groupAiLayers,
  isUnrecorded,
  layerKindLabel,
  layerKindNoun,
  layerProvenance,
  readAiPayload,
  tierLabel,
  tierSentence,
  type DwAiLayer
} from "@/lib/aiLayers";

const SERVER_SHAPE: DwAiLayer = JSON.parse(`
{
  "id": "cmlayer00000000000000raw",
  "designWorkshopId": "cmworkshop0000000000000a",
  "kind": "RAW_TRANSCRIPT",
  "tier": "TIER_3",
  "source": { "kind": "MEDIA", "id": "cmmedia000000000000000a", "text": null },
  "provider": "UNRECORDED",
  "modelId": "UNRECORDED",
  "modelVersion": null,
  "language": null,
  "sourceLanguage": null,
  "targetLanguage": null,
  "producedAt": null,
  "createdAt": "2026-08-12T09:15:00+00:00",
  "createdById": "cmuser00000000000000001",
  "accepted": false,
  "acceptedAt": null,
  "acceptedById": null,
  "textChars": 18422,
  "preview": "**Interviewer:** Tell me how the dabu paste is prepared.",
  "payload": null,
  "textWithheld": false,
  "deletedAt": null
}
`);

function layer(overrides: Partial<DwAiLayer>): DwAiLayer {
  return { ...SERVER_SHAPE, ...overrides };
}

function from(kind: string, id: string | null): DwAiLayer["source"] {
  return { kind, id, text: null };
}

test("a cleaned transcript is drawn INSIDE the raw one it was made from, not beside it", () => {
  const raw = layer({ id: "raw", createdAt: "2026-08-12T09:00:00+00:00" });
  const cleaned = layer({
    id: "cleaned",
    kind: "CLEANED_TRANSCRIPT",
    source: from("LAYER", "raw"),
    createdAt: "2026-08-12T09:00:01+00:00"
  });
  const summary = layer({
    id: "summary",
    kind: "SUMMARY",
    source: from("LAYER", "cleaned"),
    createdAt: "2026-08-12T09:30:00+00:00"
  });

  const { recordings, unrooted } = groupAiLayers([summary, cleaned, raw]);

  expect(unrooted).toEqual([]);
  expect(recordings).toHaveLength(1);
  expect(recordings[0].mediaId).toBe("cmmedia000000000000000a");
  expect(recordings[0].roots).toHaveLength(1);

  const [rawNode] = recordings[0].roots;
  expect(rawNode.layer.id).toBe("raw");
  expect(rawNode.depth).toBe(0);
  expect(rawNode.children.map((child) => child.layer.id)).toEqual(["cleaned"]);
  expect(rawNode.children[0].depth).toBe(1);
  expect(rawNode.children[0].children.map((child) => child.layer.id)).toEqual(["summary"]);
  expect(rawNode.children[0].children[0].depth).toBe(2);
});

test("two models over ONE recording are grouped together — the §2.1 safeguard", () => {
  const cloud = layer({ id: "cloud", tier: "TIER_3", createdAt: "2026-08-12T09:00:00+00:00" });
  const handset = layer({ id: "handset", tier: "TIER_1", createdAt: "2026-08-12T10:00:00+00:00" });

  const { recordings } = groupAiLayers([handset, cloud]);
  expect(recordings).toHaveLength(1);
  expect(recordings[0].roots.map((root) => root.layer.id)).toEqual(["handset", "cloud"]);
  expect(recordings[0].roots.map((root) => tierLabel(root.layer.tier))).toEqual(["On the handset", "In the cloud"]);
  expect(tierSentence("TIER_1")).not.toBe(tierSentence("TIER_3"));
});

test("no layer is ever dropped: a missing source, a missing parent and a cycle are all reported", () => {
  const rooted = layer({ id: "rooted" });
  const noSource = layer({ id: "no-source", source: null });
  const orphan = layer({ id: "orphan", kind: "SUMMARY", source: from("LAYER", "a-layer-not-in-this-list") });
  const loopA = layer({ id: "loop-a", kind: "SUMMARY", source: from("LAYER", "loop-b") });
  const loopB = layer({ id: "loop-b", kind: "SUMMARY", source: from("LAYER", "loop-a") });

  const items = [rooted, noSource, orphan, loopA, loopB];
  const grouping = groupAiLayers(items);

  const reasons = Object.fromEntries(grouping.unrooted.map((entry) => [entry.node.layer.id, entry.reason]));
  expect(reasons["no-source"]).toBe("NO_SOURCE");
  expect(reasons["orphan"]).toBe("SOURCE_NOT_LISTED");
  expect(reasons["loop-a"]).toBe("CIRCULAR");
  expect(reasons["loop-b"]).toBeUndefined();

  const rendered = [
    ...flattenAiLayerNodes(grouping.recordings.flatMap((group) => group.roots)),
    ...flattenAiLayerNodes(grouping.unrooted.map((entry) => entry.node))
  ].map((node) => node.layer.id);
  expect(rendered.slice().sort()).toEqual(items.map((entry) => entry.id).slice().sort());
});

test("a source kind this build has never heard of is said to be unknown, not diagnosed as a cycle", () => {
  const future = layer({ id: "future", source: from("COLLECTION", "cmthing0000000000000000a") });
  const grouping = groupAiLayers([future]);

  expect(grouping.recordings).toEqual([]);
  expect(grouping.unrooted.map((entry) => entry.reason)).toEqual(["UNKNOWN_SOURCE"]);
  expect(grouping.unrooted[0].node.layer.id).toBe("future");
});

test("UNRECORDED becomes the words “not recorded”, and is never blank and never a guessed provider", () => {
  const provenance = layerProvenance(SERVER_SHAPE);
  expect(provenance.provider).toBe("not recorded");
  expect(provenance.model).toBe("not recorded");
  expect(provenance.language).toBe("not recorded");
  expect(provenance.unrecorded).toBe(true);

  expect(provenance.provider).not.toContain(UNRECORDED);
  expect(isUnrecorded("unrecorded")).toBe(true);
  expect(isUnrecorded("deepgram")).toBe(false);
});

test("a recorded provenance is printed verbatim, and one recorded half does not excuse the other", () => {
  const known = layerProvenance(
    layer({ provider: "deepgram", modelId: "nova-3", modelVersion: "2026-02-11", language: "or" })
  );
  expect(known.provider).toBe("deepgram");
  expect(known.model).toBe("nova-3 · 2026-02-11");
  expect(known.language).toBe("or");
  expect(known.unrecorded).toBe(false);

  const half = layerProvenance(layer({ provider: "deepgram", modelId: UNRECORDED }));
  expect(half.provider).toBe("deepgram");
  expect(half.model).toBe("not recorded");
  expect(half.unrecorded).toBe(false);
});

test("“multi” is a real language answer, not a placeholder", () => {
  const provenance = layerProvenance(layer({ language: "multi" }));
  expect(provenance.language).toContain("multi");
  expect(provenance.language).not.toBe("not recorded");
  expect(provenance.language.length).toBeGreaterThan("multi".length);
});

test("the tier is said as what it MEANS, and never as a number to compare", () => {
  for (const tier of ["TIER_1", "TIER_2", "TIER_3"]) {
    expect(tierLabel(tier)).not.toMatch(/\d/);
    expect(tierSentence(tier).length).toBeGreaterThan(40);
  }
  expect(tierLabel("TIER_1")).toBe("On the handset");
  expect(tierLabel("TIER_3")).toBe("In the cloud");
});

test("a kind or tier this build has never heard of degrades to an honest note, never to a blank", () => {
  expect(layerKindLabel("DIARIZATION")).toContain("DIARIZATION");
  expect(layerKindLabel("DIARIZATION")).toContain("unfamiliar");
  expect(layerKindLabel("")).toBe("A layer with no kind recorded");
  expect(tierLabel("TIER_4")).toContain("TIER_4");
  expect(tierLabel("")).toBe("Tier not recorded");
  expect(tierSentence("TIER_4")).toContain("Reload the page to update the app");
});

test("the plain-word labels distinguish model prose from the artisan's own words", () => {
  expect(layerKindLabel("RAW_TRANSCRIPT")).toBe("Machine transcript");
  expect(layerKindLabel("CLEANED_TRANSCRIPT")).toContain("AI");
  expect(layerKindLabel("SUMMARY")).toContain("AI");
  for (const kind of ["RAW_TRANSCRIPT", "CLEANED_TRANSCRIPT", "SUMMARY", "OCR_TEXT", "STRUCTURED_TEXT", "TAGS", "METADATA"]) {
    expect(layerKindLabel(kind)).not.toBe(kind);
    expect(layerKindLabel(kind)).not.toContain("_");
  }
});

test("a kind can be dropped into a sentence without the sentence falling apart", () => {
  expect(layerKindNoun("RAW_TRANSCRIPT")).toBe("machine transcript");
  expect(layerKindNoun("DIARIZATION")).toBe("layer");
  expect(layerKindNoun(null)).toBe("layer");
  for (const kind of ["RAW_TRANSCRIPT", "TAGS", "DIARIZATION", "", null]) {
    const sentence = `Accept this ${layerKindNoun(kind)} in your name?`;
    expect(sentence).not.toContain("this a ");
    expect(sentence).not.toContain("()");
  }
});

test("the server's own refusal is passed through word for word", () => {
  const detail =
    "This layer has already been accepted. Withdraw the acceptance first if it should stand in " +
    "somebody else's name.";
  expect(aiLayerProblem(new ApiError(409, detail, { detail }), "unused")).toBe(detail);

  const parsed = "producedAt: producedAt must be an ISO-8601 moment such as 2026-03-04T11:20:00Z.";
  const body = { detail: [{ msg: "Value error, producedAt must be…", loc: ["body", "producedAt"] }] };
  expect(aiLayerProblem(new ApiError(422, parsed, body), "unused")).toBe(parsed);
});

test("a reply that carried no reason never reaches the designer as a status code", () => {
  const bodyless = new ApiError(502, "The server refused the request (HTTP 502).", "<html>502 Bad Gateway</html>");
  const shown = aiLayerProblem(bodyless, "unused");
  expect(shown).not.toContain("502");
  expect(shown).not.toMatch(/HTTP \d/);
  expect(shown).toContain("Reload");
  expect(shown).toContain("isn't clear whether your change was saved");

  expect(aiLayerProblem(new ApiError(500, "Internal Server Error", { error: "boom" }), "unused")).not.toMatch(/\d{3}/);
});

test("an offline failure says nothing is queued, because nothing is", () => {
  const offline = aiLayerProblem(new TypeError("Failed to fetch"), "unused");
  expect(offline).toContain("without a connection");
  expect(offline).toContain("Nothing was saved and nothing will be sent later");
  expect(offline).toContain("Reload");
  expect(aiLayerProblem(new ApiError(408, "", null), "unused")).toBe(offline);
});

test("a payload in a plausible but wrong shape produces the honest answer, never a confident one", () => {
  const wrong = JSON.parse(`
    {
      "id": "wrong",
      "kind": "RAW_TRANSCRIPT",
      "tier": "TIER_3",
      "model": "nova-3",
      "provider": "deepgram",
      "withheld": false,
      "sourceMediaId": "cmmedia000000000000000a",
      "accepted": false
    }
  `) as DwAiLayer;

  expect(layerProvenance(wrong).model).toBe("not recorded");
  const grouping = groupAiLayers([wrong]);
  expect(grouping.recordings).toEqual([]);
  expect(grouping.unrooted.map((entry) => entry.reason)).toEqual(["NO_SOURCE"]);
});

test("a withheld layer keeps its provenance and loses only the recording's content", () => {
  const withheld = layer({
    id: "withheld",
    textWithheld: true,
    preview: null,
    textChars: null,
    payload: null,
    provider: "deepgram",
    modelId: "nova-3"
  });
  expect(withheld.textChars).toBeNull();
  expect(layerProvenance(withheld).provider).toBe("deepgram");
  expect(groupAiLayers([withheld]).recordings[0].roots[0].layer.id).toBe("withheld");
});

test("a structured payload is drawn as what it is, and a truncation says so", () => {
  expect(readAiPayload(null)).toBeNull();
  expect(readAiPayload(["dabu", "mud resist", "indigo"])).toEqual({
    shape: "TAGS",
    tags: ["dabu", "mud resist", "indigo"]
  });

  expect(readAiPayload({ village: "Bagru", loomCount: 4, verified: false, dyer: null })).toEqual({
    shape: "ROWS",
    rows: [
      { key: "village", value: "Bagru" },
      { key: "loomCount", value: "4" },
      { key: "verified", value: "false" },
      { key: "dyer", value: "not recorded" }
    ]
  });

  const long = readAiPayload({ turns: Array.from({ length: 400 }, (_, index) => `speaker ${index} said something`) });
  expect(long?.shape).toBe("JSON");
  expect(long).toMatchObject({ truncated: true });
});

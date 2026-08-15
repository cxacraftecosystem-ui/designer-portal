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

/**
 * The browser's reading of `GET /design-workshops/{id}/ai-layers`, and the four rules the screen over
 * it is not allowed to break.
 *
 * WHY THIS SPEC NEEDS NEITHER CREDENTIALS NOR A BACKEND, unlike most of this suite. Everything
 * asserted here is a pure function of a decoded payload: which chain a layer belongs to, what its
 * provenance says in words, and what the screen prints when a fact was never recorded. Those are the
 * decisions that turn a table into a page, they are decidable with no network, and they are exactly
 * the decisions a browser spec would be the slowest possible way to check. `identity-ocr-unit.spec.ts`
 * is the same shape for the same reason.
 *
 * ⚠ HOW `SERVER_SHAPE` WAS BUILT, STATED BECAUSE THE DIFFERENCE MATTERS. It was transcribed
 * key-for-key from `ai_layers.layer_payload` in `backend/app/services/ai_layers.py` — every key, in
 * that function's own order — and **not** captured from a running server, which this machine has no
 * database for. So it pins that this client reads the keys the SERVER'S CODE writes; it does not
 * prove the deployed server writes them. A round trip against a live API would be strictly stronger
 * and should replace this constant the first time one is available.
 *
 * THE DEFECT THIS SHAPE OF SPEC EXISTS FOR. `DwIdentityOcrResult` once declared `number`,
 * `documentType`, `name`, `confidence` and `message` — none of which that endpoint has ever sent.
 * Decoding JSON ignores unknown keys, so nothing threw: a PERFECT read of an identity card was
 * reported to the designer as unreadable, and Android's DTO carried the identical bug against the
 * identical payload. The regression witnesses below are the counterpart of that spec's: a payload in
 * a plausible but WRONG shape must produce the honest-unknown answer, never a confident one.
 */

/** One layer exactly as `layer_payload` serialises it — the raw rung of a chain over a recording. */
const SERVER_SHAPE: DwAiLayer = JSON.parse(`
{
  "id": "cmlayer00000000000000raw",
  "designWorkshopId": "cmworkshop0000000000000a",
  "kind": "RAW_TRANSCRIPT",
  "tier": "TIER_3",
  "source": { "kind": "MEDIA", "id": "cmmedia000000000000000a" },
  "provider": "UNRECORDED",
  "modelId": "UNRECORDED",
  "modelVersion": null,
  "language": null,
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

/* ────────────────────────────────────────────────────────────────────────────
 * The chain
 * ──────────────────────────────────────────────────────────────────────────── */

test("a cleaned transcript is drawn INSIDE the raw one it was made from, not beside it", () => {
  const raw = layer({ id: "raw", createdAt: "2026-08-12T09:00:00+00:00" });
  const cleaned = layer({
    id: "cleaned",
    kind: "CLEANED_TRANSCRIPT",
    source: { kind: "LAYER", id: "raw" },
    createdAt: "2026-08-12T09:00:01+00:00"
  });
  const summary = layer({
    id: "summary",
    kind: "SUMMARY",
    source: { kind: "LAYER", id: "cleaned" },
    createdAt: "2026-08-12T09:30:00+00:00"
  });

  // Newest first is the order the endpoint returns rows in, so the grouping must not depend on the
  // parent arriving before its child.
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
  /*
    The plan's whole justification for recording the tier is that a phone-produced and a
    cloud-produced transcript of the same clip must both EXIST and be tellable apart on the page. A
    grouping that keyed on (recording, kind) — or a unique index, which is why the server has none —
    would have hidden one of them; a grouping that keyed on date alone would have put them in
    different parts of a list. Both must sit under one heading with their tiers showing.
  */
  const cloud = layer({ id: "cloud", tier: "TIER_3", createdAt: "2026-08-12T09:00:00+00:00" });
  const handset = layer({ id: "handset", tier: "TIER_1", createdAt: "2026-08-12T10:00:00+00:00" });

  const { recordings } = groupAiLayers([handset, cloud]);
  expect(recordings).toHaveLength(1);
  expect(recordings[0].roots.map((root) => root.layer.id)).toEqual(["handset", "cloud"]);
  expect(recordings[0].roots.map((root) => tierLabel(root.layer.tier))).toEqual(["On the handset", "In the cloud"]);
  // And the two labels are genuinely different sentences, so the page cannot present them alike.
  expect(tierSentence("TIER_1")).not.toBe(tierSentence("TIER_3"));
});

test("no layer is ever dropped: a missing source, a missing parent and a cycle are all reported", () => {
  /*
    THE SILENT-EMPTINESS CLASS, which is this repository's most repeated defect. A layer that fell out
    of the grouping would be indistinguishable on screen from a layer that was never made — and two of
    these three shapes are exactly what a hand-run backfill produces, which is the case the server's
    own CHECK constraint and its `MAX_CHAIN_HOPS` bound exist for.
  */
  const rooted = layer({ id: "rooted" });
  const noSource = layer({ id: "no-source", source: null });
  const orphan = layer({ id: "orphan", kind: "SUMMARY", source: { kind: "LAYER", id: "a-layer-not-in-this-list" } });
  const loopA = layer({ id: "loop-a", kind: "SUMMARY", source: { kind: "LAYER", id: "loop-b" } });
  const loopB = layer({ id: "loop-b", kind: "SUMMARY", source: { kind: "LAYER", id: "loop-a" } });

  const items = [rooted, noSource, orphan, loopA, loopB];
  const grouping = groupAiLayers(items);

  const reasons = Object.fromEntries(grouping.unrooted.map((entry) => [entry.node.layer.id, entry.reason]));
  expect(reasons["no-source"]).toBe("NO_SOURCE");
  expect(reasons["orphan"]).toBe("SOURCE_NOT_LISTED");
  // A cycle is entered once and terminates; the second member hangs under the first rather than
  // appearing twice or vanishing.
  expect(reasons["loop-a"]).toBe("CIRCULAR");
  expect(reasons["loop-b"]).toBeUndefined();

  // Conservation, asserted as a set rather than a count so a duplicate fails too.
  const rendered = [
    ...flattenAiLayerNodes(grouping.recordings.flatMap((group) => group.roots)),
    ...flattenAiLayerNodes(grouping.unrooted.map((entry) => entry.node))
  ].map((node) => node.layer.id);
  expect(rendered.slice().sort()).toEqual(items.map((entry) => entry.id).slice().sort());
});

test("a source kind this build has never heard of is said to be unknown, not diagnosed as a cycle", () => {
  /*
    `DwAiLayerSource.kind` is widened with `string` on purpose, so a server one release ahead can send
    a third kind of thing to derive from. Before this was its own reason, such a row fell through to
    CIRCULAR and the screen told a designer "this layer's chain leads back to itself" — a confident
    diagnosis of a defect that does not exist, about a perfectly well formed row. That is the
    fabricated-fact failure this whole feature exists to prevent, committed by the client rather than
    by a model, which is why it gets a witness of its own.
  */
  const future = layer({ id: "future", source: { kind: "COLLECTION", id: "cmthing0000000000000000a" } });
  const grouping = groupAiLayers([future]);

  expect(grouping.recordings).toEqual([]);
  expect(grouping.unrooted.map((entry) => entry.reason)).toEqual(["UNKNOWN_SOURCE"]);
  // And it is still on the page, with its provenance, rather than dropped for being unplaceable.
  expect(grouping.unrooted[0].node.layer.id).toBe("future");
});

/* ────────────────────────────────────────────────────────────────────────────
 * Honest unknowns
 * ──────────────────────────────────────────────────────────────────────────── */

test("UNRECORDED becomes the words “not recorded”, and is never blank and never a guessed provider", () => {
  /*
    This is the COMMON case on day one, not an edge case: `media_queue._transcript_write` stores the
    transcript, its summary, its status and its error — and not which of the four providers in
    `services/ai.py` produced it. Rendering it as blank would read as "there is nothing here", and
    naming a plausible provider would be a fabricated provenance record in the one table built to
    prevent them.
  */
  const provenance = layerProvenance(SERVER_SHAPE);
  expect(provenance.provider).toBe("not recorded");
  expect(provenance.model).toBe("not recorded");
  expect(provenance.language).toBe("not recorded");
  expect(provenance.unrecorded).toBe(true);

  // The token itself never reaches a screen.
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

  // `unrecorded` means NOTHING about the run is known — it drives a whole sentence on the page, so a
  // row that names its provider but not its model must not claim to be untraceable.
  const half = layerProvenance(layer({ provider: "deepgram", modelId: UNRECORDED }));
  expect(half.provider).toBe("deepgram");
  expect(half.model).toBe("not recorded");
  expect(half.unrecorded).toBe(false);
});

test("“multi” is a real language answer, not a placeholder", () => {
  // Deepgram Nova-3 is called with `language=multi` precisely because a workshop is Hindi
  // code-switched with English mid-sentence. Printing the bare token would read like a missing value.
  const provenance = layerProvenance(layer({ language: "multi" }));
  expect(provenance.language).toContain("multi");
  expect(provenance.language).not.toBe("not recorded");
  expect(provenance.language.length).toBeGreaterThan("multi".length);
});

test("the tier is said as what it MEANS, and never as a number to compare", () => {
  /*
    Plan §2.1: without the tier on the page, a cloud-diarized interview and a device-guessed one look
    alike. `AiTier.number` exists on the server "for prose only, never for a comparison" — Tier 1 is
    the only tier that works with no signal and Tier 3 is the only one carrying the craft keyterm
    list, so higher is not better in either direction, and a chip reading "Tier 3" invites exactly
    that comparison.
  */
  for (const tier of ["TIER_1", "TIER_2", "TIER_3"]) {
    expect(tierLabel(tier)).not.toMatch(/\d/);
    expect(tierSentence(tier).length).toBeGreaterThan(40);
  }
  expect(tierLabel("TIER_1")).toBe("On the handset");
  expect(tierLabel("TIER_3")).toBe("In the cloud");
});

test("a kind or tier this build has never heard of degrades to an honest note, never to a blank", () => {
  // A server one release ahead can legitimately send a kind this client does not know. The row still
  // carries a readable tier, model and acceptance, so hiding it would lose more than it protects —
  // and a blank heading would be indistinguishable from a rendering bug. Android's
  // `UNSUPPORTED_SECTIONS` precedent, applied here.
  expect(layerKindLabel("DIARIZATION")).toContain("DIARIZATION");
  expect(layerKindLabel("DIARIZATION")).toContain("does not know");
  expect(layerKindLabel("")).toBe("A layer with no kind recorded");
  expect(tierLabel("TIER_4")).toContain("TIER_4");
  expect(tierLabel("")).toBe("Tier not recorded");
  expect(tierSentence("TIER_4")).toContain("does not know");
});

test("the plain-word labels distinguish model prose from the artisan's own words", () => {
  // Rule 4's failure mode, one heading earlier than the report: a workshop also holds transcripts a
  // person typed or corrected through `POST /media/{id}/transcript`, so a heading that read only
  // "Transcript" would let model output be taken for somebody's own words.
  expect(layerKindLabel("RAW_TRANSCRIPT")).toBe("Machine transcript");
  expect(layerKindLabel("CLEANED_TRANSCRIPT")).toContain("AI");
  expect(layerKindLabel("SUMMARY")).toContain("AI");
  // And no label is the bare enum member, which is what the plan asked for in so many words.
  for (const kind of ["RAW_TRANSCRIPT", "CLEANED_TRANSCRIPT", "SUMMARY", "OCR_TEXT", "STRUCTURED_TEXT", "TAGS", "METADATA"]) {
    expect(layerKindLabel(kind)).not.toBe(kind);
    expect(layerKindLabel(kind)).not.toContain("_");
  }
});

test("a kind can be dropped into a sentence without the sentence falling apart", () => {
  /*
    `layerKindLabel` degrades an unfamiliar kind to a whole SENTENCE, which is right for a heading and
    wrong inside another sentence: interpolating it produced the confirm title "Accept this a layer
    kind this screen does not know (DIARIZATION) in your name?" — broken English at the exact moment
    somebody is being asked to put their name to model output. `layerKindNoun` is the embeddable form.
  */
  expect(layerKindNoun("RAW_TRANSCRIPT")).toBe("machine transcript");
  expect(layerKindNoun("DIARIZATION")).toBe("layer");
  expect(layerKindNoun(null)).toBe("layer");
  for (const kind of ["RAW_TRANSCRIPT", "TAGS", "DIARIZATION", "", null]) {
    const sentence = `Accept this ${layerKindNoun(kind)} in your name?`;
    expect(sentence).not.toContain("this a ");
    expect(sentence).not.toContain("()");
  }
});

/* ────────────────────────────────────────────────────────────────────────────
 * Refusals — the sentence a failure actually reaches the designer as
 * ──────────────────────────────────────────────────────────────────────────── */

test("the server's own refusal is passed through word for word", () => {
  // Every refusal these five routes raise is already a sentence naming the next move, and rewording
  // one here would give a single rule two voices.
  const detail =
    "This layer has already been accepted. Withdraw the acceptance first if it should stand in " +
    "somebody else's name.";
  expect(aiLayerProblem(new ApiError(409, detail, { detail }), "unused")).toBe(detail);

  // A FastAPI 422's `detail` is a LIST; `apiFetch` has already turned it into a sentence, and what
  // matters here is that a body which carried one is recognised as the server having spoken.
  const parsed = "producedAt: producedAt must be an ISO-8601 moment such as 2026-03-04T11:20:00Z.";
  const body = { detail: [{ msg: "Value error, producedAt must be…", loc: ["body", "producedAt"] }] };
  expect(aiLayerProblem(new ApiError(422, parsed, body), "unused")).toBe(parsed);
});

test("a reply that carried no reason never reaches the designer as a status code", () => {
  /*
    THE DEFECT: `apiFetch` builds its message as `describeApiDetail(detail, statusText || "The server
    refused the request (HTTP {status}).")`, and `statusText` is empty over HTTP/2 — which every
    deployed request is. So a proxy's 502 page or a gateway timeout arrived with the literal string
    "The server refused the request (HTTP 502)." as its message and was shown as the answer, under a
    docstring and a panel header that BOTH promise a status code is never shown. The body is the only
    place the difference between "the server said this" and "apiFetch made this up" is visible.
  */
  const bodyless = new ApiError(502, "The server refused the request (HTTP 502).", "<html>502 Bad Gateway</html>");
  const shown = aiLayerProblem(bodyless, "unused");
  expect(shown).not.toContain("502");
  expect(shown).not.toMatch(/HTTP \d/);
  // It names the next move, and it deliberately does NOT claim the write did not land — a 502 from a
  // proxy sits perfectly happily in front of a write that succeeded.
  expect(shown).toContain("Reload");
  expect(shown).toContain("cannot tell whether the change was recorded");

  // The same holds for a 500 with a JSON body that has no `detail` at all.
  expect(aiLayerProblem(new ApiError(500, "Internal Server Error", { error: "boom" }), "unused")).not.toMatch(/\d{3}/);
});

test("an offline failure says nothing is queued, because nothing is", () => {
  /*
    Every other write in this app banks itself in the outbox and drains later, so a bare "nothing has
    been lost" — which is what this said — invites the reading that the acceptance is waiting to be
    sent. It is not, and deliberately: an acceptance is a signature, the server refuses a second one,
    and a signature replayed three days later against a layer somebody has since declined would report
    success for an act that never happened.
  */
  const offline = aiLayerProblem(new TypeError("Failed to fetch"), "unused");
  expect(offline).toContain("without a connection");
  expect(offline).toContain("Nothing has been queued for later");
  expect(offline).toContain("Reload");
  // A 408 is the one status that means the request never completed, so it is the offline answer too.
  expect(aiLayerProblem(new ApiError(408, "", null), "unused")).toBe(offline);
});

/* ────────────────────────────────────────────────────────────────────────────
 * Regression witnesses — the "five keys the endpoint never sent" defect
 * ──────────────────────────────────────────────────────────────────────────── */

test("a payload in a plausible but wrong shape produces the honest answer, never a confident one", () => {
  /*
    Kept as a witness so that "tidying" these key names from memory fails here and says why. `model`
    is not a key (`modelId` is), `withheld` is not a key (`textWithheld` is), and the source is an
    OBJECT rather than the two flat columns the DATABASE holds — `layer_payload` reassembles them
    before they cross the wire.
  */
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

  // The model is read from `modelId`, which this object does not have — so it must say "not
  // recorded" rather than finding `model` and reporting a provenance the server never sent.
  expect(layerProvenance(wrong).model).toBe("not recorded");
  // And a flat `sourceMediaId` is not a source: the layer is reported as unplaceable, loudly, rather
  // than being silently attached to a recording.
  const grouping = groupAiLayers([wrong]);
  expect(grouping.recordings).toEqual([]);
  expect(grouping.unrooted.map((entry) => entry.reason)).toEqual(["NO_SOURCE"]);
});

test("a withheld layer keeps its provenance and loses only the recording's content", () => {
  /*
    Who may read a transcript is decided PER MEDIA FILE and not by who may open the workshop — a
    `DesignWorkshopViewer` grant carries read and stage writes and says nothing about media. So the
    four content keys go and the provenance stays, which is what the reviewer came for. `textChars` is
    null rather than 0 deliberately: 0 would say "there is nothing to read", which is a different fact
    and the only one of the two that is false.
  */
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
  // It is still placed in its chain: withholding text must not remove the row from the page.
  expect(groupAiLayers([withheld]).recordings[0].roots[0].layer.id).toBe("withheld");
});

/* ────────────────────────────────────────────────────────────────────────────
 * The structured payload
 * ──────────────────────────────────────────────────────────────────────────── */

test("a structured payload is drawn as what it is, and a truncation says so", () => {
  expect(readAiPayload(null)).toBeNull();
  expect(readAiPayload(["dabu", "mud resist", "indigo"])).toEqual({
    shape: "TAGS",
    tags: ["dabu", "mud resist", "indigo"]
  });

  // Declaration order is kept, every scalar is printed as itself — and a null VALUE is the model
  // having nothing for that field, which is the same honest unknown as an unrecorded provider and
  // gets the same words rather than an empty cell.
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

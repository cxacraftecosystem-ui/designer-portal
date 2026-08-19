import { readFileSync } from "node:fs";
import path from "node:path";

import { expect, test } from "@playwright/test";

import {
  aiVerbPlacementAllows,
  groupAiLayers,
  layerKindLabel,
  layerKindNote,
  layerKindNoun,
  layerLanguagePair,
  type DwAiLayer
} from "@/lib/aiLayers";
import {
  MAX_VERB_TEXT_CHARS,
  WORKSHOP_NOT_ON_SERVER_YET,
  aiVerbsSpent,
  captionDesignWorkshopMedia,
  consentNotGranted,
  expandDesignWorkshopNote,
  proofreadDesignWorkshopText,
  selectedPassage,
  subtitleCueSummary,
  subtitleDesignWorkshopMedia,
  translateDesignWorkshopText,
  translationTargetRefusal,
  verbAllowanceRefusal,
  verbWorkshopRefusal,
  type DwAiVerbAllowance,
  type DwAiVerbAllowanceState
} from "@/lib/aiVerbs";
import { fromPlainText, type DocRange } from "@/lib/richText";
import { mergeDraftConsent } from "@/lib/designWorkshopStore";

/**
 * The browser's half of the five AI verbs, and the rules the three surfaces over them may not break.
 *
 * WHY THIS SPEC NEEDS NEITHER CREDENTIALS NOR A BACKEND, exactly as `ai-layers-unit.spec.ts` does
 * not: everything asserted here is either a pure function of a decoded payload, a request body built
 * from arguments, or a fact about a source file. Those are the decisions that turn a payload into a
 * page and a click into a request, they are decidable with no network, and a browser spec would be
 * the slowest possible way to check them.
 *
 * ⚠ HOW THE SHAPES BELOW WERE BUILT, STATED BECAUSE THE DIFFERENCE MATTERS. `VERB_201` was
 * transcribed key-for-key from `design_workshops._finish_verb` and `ai_layers.layer_payload` in the
 * backend source — every key, in those functions' own order — and **not** captured from a running
 * server, which this machine has no database for. So it pins that this client reads the keys the
 * SERVER'S CODE writes; it does not prove the deployed server writes them. A round trip against a
 * live API would be strictly stronger and should replace these constants the first time one is
 * available.
 *
 * THE DEFECT THIS SHAPE OF SPEC EXISTS FOR is the one `lib/aiLayers.ts`'s header records:
 * `DwIdentityOcrResult` declared five keys the endpoint had never sent, decoding JSON ignores
 * unknown keys so nothing threw, and a PERFECT read of an identity card was reported to a designer
 * as unreadable. Every "regression witness" below is the counterpart of that: a plausible but WRONG
 * shape must produce the honest-unknown answer, never a confident one.
 */

/* ────────────────────────────────────────────────────────────────────────────
 * The 201, and one layer inside it
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * `_finish_verb`'s 201, key for key: `layer` (with text), `accepted`, `acceptanceRequired`, and the
 * five keys of `ai_verb_cap.allowance_payload` spread in beside them.
 *
 * The layer is a PROOFREAD rooted in supplied text, which is the shape the web produces most and the
 * one that was un-renderable before this lane: `SUPPLIED_TEXT` was absent from `DwAiSourceKind`,
 * `source.id` was typed `string` where the server sends null, `source.text` was not declared at all,
 * and neither language column existed.
 */
const VERB_201 = JSON.parse(`
{
  "layer": {
    "id": "cmlayer00000000000proofread",
    "designWorkshopId": "cmworkshop0000000000000a",
    "kind": "PROOFREAD",
    "tier": "TIER_3",
    "source": { "kind": "SUPPLIED_TEXT", "id": null, "text": "warp sized w/ rice paste, 2 dips" },
    "provider": "OPENAI",
    "modelId": "gpt-4o-mini",
    "modelVersion": null,
    "language": null,
    "sourceLanguage": null,
    "targetLanguage": null,
    "producedAt": "2026-08-19T05:12:00+00:00",
    "createdAt": "2026-08-19T05:12:01+00:00",
    "createdById": "cmuser00000000000000001",
    "accepted": false,
    "acceptedAt": null,
    "acceptedById": null,
    "textChars": 33,
    "preview": "Warp sized with rice paste, two dips.",
    "payload": null,
    "textWithheld": false,
    "deletedAt": null,
    "text": "Warp sized with rice paste, two dips."
  },
  "accepted": false,
  "acceptanceRequired": true,
  "aiVerbsLimit": 40,
  "aiVerbsUsed": 6,
  "aiVerbsRemaining": 34,
  "aiVerbDay": "2026-08-19",
  "aiVerbsByVerb": { "PROOFREAD": 4, "CAPTION": 2 }
}
`);

function layerOf(overrides: Partial<DwAiLayer>): DwAiLayer {
  return { ...(VERB_201.layer as DwAiLayer), ...overrides };
}

/* ────────────────────────────────────────────────────────────────────────────
 * The request bodies — captured off a stubbed fetch
 * ──────────────────────────────────────────────────────────────────────────── */

type Sent = { url: string; body: Record<string, unknown> };

/**
 * Run one call with `fetch` replaced, and hand back what actually went on the wire.
 *
 * A STUB RATHER THAN A MOCK LIBRARY, because the thing under test is the BODY and nothing else:
 * `apiFetch` returns early from `assertApiConfigured` outside a browser and `getToken` answers null
 * there, so the only moving part left is the JSON these six builders compose.
 */
async function capture(run: () => Promise<unknown>): Promise<Sent> {
  const original = globalThis.fetch;
  let seen: Sent | null = null;
  globalThis.fetch = (async (url: string, init: RequestInit) => {
    seen = { url: String(url), body: JSON.parse(String(init?.body ?? "{}")) };
    return new Response(JSON.stringify(VERB_201), {
      status: 201,
      headers: { "content-type": "application/json" }
    });
  }) as unknown as typeof fetch;
  try {
    await run();
  } finally {
    globalThis.fetch = original;
  }
  if (!seen) throw new Error("nothing was sent");
  return seen;
}

test("a verb body names exactly one source, and expand cannot name a layer at all", async () => {
  /*
    THE MOST IMPORTANT OF THE THREE ASSERTIONS IS THE EXPAND ONE.

    `AiExpandIn` has no `sourceLayerId` field, `ai_verbs.expand` cannot express one, and
    `TEXT_ROOTED_KINDS` puts EXPANDED above supplied words and nothing else. Each refuses the same
    thing for the same reason: expanding is the one verb that INVENTS sentences, and run over an
    artisan's transcript it would put invented words in a named person's mouth in a document a
    ministry officer reads. `expandDesignWorkshopNote`'s signature is the client's copy of that
    refusal, and this test fails the moment somebody adds a layer parameter "for symmetry".
  */
  const expanded = await capture(() => expandDesignWorkshopNote("w1", "warp sized w/ rice paste"));
  expect(Object.keys(expanded.body)).toEqual(["text"]);
  expect(expanded.body).not.toHaveProperty("sourceLayerId");
  expect(expanded.url).toContain("/ai-layers/expand");

  // Proofread and translate accept EITHER shape and must send exactly one — the 422 that
  // `_require_exactly_one_source` raises is therefore unreachable from this client.
  const proofreadText = await capture(() => proofreadDesignWorkshopText("w1", { text: "a note" }));
  expect(proofreadText.body).toEqual({ text: "a note" });

  const proofreadLayer = await capture(() => proofreadDesignWorkshopText("w1", { sourceLayerId: "L1" }));
  expect(proofreadLayer.body).toEqual({ sourceLayerId: "L1" });

  const translated = await capture(() => translateDesignWorkshopText("w1", { sourceLayerId: "L1" }, "Odia"));
  expect(translated.body).toEqual({ sourceLayerId: "L1", targetLanguage: "Odia" });
  expect("text" in translated.body).toBe(false);
});

test("every key of the 201 is read, including the two the client never declared", async () => {
  /*
    THE `DwIdentityOcrResult` REGRESSION WITNESS, FOR A NEW PAYLOAD.

    That type declared five keys the endpoint had never sent; decoding JSON ignores unknown keys, so
    nothing threw and a perfect read was reported as a failure. The inverse is what happened here:
    `layer_payload` sends `sourceLanguage`, `targetLanguage` and `source.text` UNCONDITIONALLY, and
    `DwAiLayer` declared none of them — so the two columns a reviewer of a translated passage opens
    the screen FOR were invisible to every renderer, and the evidence a supplied-text layer carries
    (the only copy of it that exists) could not be shown.

    Asserted through a real call so the decode path is the one the app uses, not a cast in a test.
  */
  let decoded: Awaited<ReturnType<typeof proofreadDesignWorkshopText>> | null = null;
  await capture(async () => {
    decoded = await proofreadDesignWorkshopText("w1", { text: "a note" });
  });
  const answer = decoded as unknown as Record<string, unknown> & { layer: Record<string, unknown> };

  // `_finish_verb` puts these two on the wire rather than in documentation, "at the moment it
  // matters most — the client that just asked for this has words on screen and is one tap from
  // putting them in a report".
  expect(answer.accepted).toBe(false);
  expect(answer.acceptanceRequired).toBe(true);

  // The five allowance keys, spread in from `ai_verb_cap.allowance_payload`.
  expect(answer.aiVerbsLimit).toBe(40);
  expect(answer.aiVerbsUsed).toBe(6);
  expect(answer.aiVerbsRemaining).toBe(34);
  expect(answer.aiVerbDay).toBe("2026-08-19");
  expect(answer.aiVerbsByVerb).toEqual({ PROOFREAD: 4, CAPTION: 2 });

  /*
    THE TWO LAYER KEYS THIS CLIENT DID NOT HAVE, asserted through the function that READS them rather
    than by looking them up on the payload. A `toHaveProperty` on the transcribed constant would pin
    the constant against itself and pass with the client still ignoring the columns, which is the
    worthless shape of test this repository has caught before.
  */
  expect(layerLanguagePair(answer.layer as unknown as DwAiLayer)).toBeNull();
  expect(
    layerLanguagePair(layerOf({ sourceLanguage: "multi", targetLanguage: "English" }))
  ).toEqual({ from: "multi — mixed, code-switched speech", into: "English" });
  // `UNRECORDED` is what the column holds when the run detected no source language, and it is an
  // ordinary answer rather than a fault — printed as words, never as a bare token.
  expect(layerLanguagePair(layerOf({ sourceLanguage: "UNRECORDED", targetLanguage: "Odia" }))).toEqual({
    from: "not recorded",
    into: "Odia"
  });

  expect((answer.layer.source as Record<string, unknown>).text).toBe("warp sized w/ rice paste, 2 dips");
  // And the text itself, which is present only because `_finish_verb` passes `include_text=True` —
  // the list route does not, so this is the one moment the words exist on the client at all.
  expect(answer.layer.text).toBe("Warp sized with rice paste, two dips.");
});

test("a media verb sends the media id, and subtitles cannot be asked for in a language", async () => {
  const caption = await capture(() => captionDesignWorkshopMedia("w1", "m1"));
  expect(caption.body).toEqual({ sourceMediaId: "m1" });
  expect(caption.url).toContain("/ai-layers/caption");

  /*
    `AiMediaVerbIn.language` documents that "Subtitles ignore this field entirely — a cue list is in
    whatever language was spoken", so `subtitleDesignWorkshopMedia` has no such argument. Sending one
    would not be refused; it would be silently dropped, which is worse — a client would believe it
    had asked for something.
  */
  const subtitles = await capture(() => subtitleDesignWorkshopMedia("w1", "m1"));
  expect(subtitles.body).toEqual({ sourceMediaId: "m1" });
  expect(subtitles.url).toContain("/ai-layers/subtitles");
});

/* ────────────────────────────────────────────────────────────────────────────
 * The vocabulary
 * ──────────────────────────────────────────────────────────────────────────── */

test("a supplied-text layer is a root of its own and is never reported as an unknown source", () => {
  /*
    FAILS BEFORE THIS LANE. `DwAiSourceKind` was `"MEDIA" | "LAYER"`, so `groupAiLayers` fell through
    to `unrooted` with the reason `UNKNOWN_SOURCE` — and the module's own comment records that such a
    row was once mis-diagnosed as CIRCULAR and shown to a designer as "this layer's chain leads back
    to itself". A designer's own proofread of their own note, missing from the screen that exists to
    show it, under a diagnosis of a defect that does not exist.
  */
  const grouping = groupAiLayers([VERB_201.layer as DwAiLayer]);

  expect(grouping.notes).toHaveLength(1);
  expect(grouping.notes[0].layer.id).toBe("cmlayer00000000000proofread");
  expect(grouping.unrooted).toEqual([]);
  expect(grouping.recordings).toEqual([]);
  // THE EVIDENCE TRAVELS WITH THE LAYER — there is no second request that could fetch it, so a type
  // that dropped this key would leave the review surface with nothing to show as "what was sent".
  expect(grouping.notes[0].layer.source?.text).toBe("warp sized w/ rice paste, 2 dips");
});

test("a chain standing on a supplied-text root is drawn inside it, not beside it", () => {
  const root = layerOf({ id: "root", createdAt: "2026-08-19T05:00:00+00:00" });
  const derived = layerOf({
    id: "derived",
    kind: "TRANSLATION",
    source: { kind: "LAYER", id: "root", text: null },
    sourceLanguage: "Hindi",
    targetLanguage: "English",
    createdAt: "2026-08-19T05:01:00+00:00"
  });

  const grouping = groupAiLayers([derived, root]);
  expect(grouping.notes).toHaveLength(1);
  expect(grouping.notes[0].children.map((child) => child.layer.id)).toEqual(["derived"]);
  expect(grouping.unrooted).toEqual([]);
});

test("the five verb kinds are named the way the report names them, and never as an unknown kind", () => {
  /*
    The five headings are `report_ai_layers._KIND_TITLES`' own words, so the acceptance screen and
    the annexure call one thing one name — a designer who signs for "AI-corrected spelling and
    punctuation" meets that phrase again in the .docx.

    `layerKindNoun` is asserted separately because `layerKindLabel` DEGRADES TO A WHOLE SENTENCE for
    an unknown kind, and interpolating a sentence into another one produced the confirm dialog
    "Accept this a layer kind this screen does not know (PROOFREAD) in your name?" — broken English
    at the exact moment somebody is being asked to sign.
  */
  const expected: Record<string, string> = {
    PROOFREAD: "AI-corrected spelling and punctuation",
    EXPANDED: "Prose written by AI from a designer's note",
    TRANSLATION: "AI translation",
    CAPTION: "AI description of a photograph or video",
    SUBTITLES: "AI subtitles, with their timings"
  };
  for (const [kind, heading] of Object.entries(expected)) {
    expect(layerKindLabel(kind)).toBe(heading);
    expect(layerKindLabel(kind)).not.toContain("does not know");
    expect(layerKindNote(kind)).toBeTruthy();
    const sentence = `Accept this ${layerKindNoun(kind)} in your name?`;
    expect(sentence).not.toContain("does not know");
    expect(sentence).not.toContain("layer kind");
  }

  // EXPANDED's note must carry the substance of `report_ai_layers.EXPANDED_NOTE`, which the annexure
  // prints under that heading and under no other. The caution a ministry officer reads has to be the
  // caution the designer read before signing.
  expect(layerKindNote("EXPANDED")).toContain("was not recorded in the field");

  // The regression witness: a kind this build genuinely does not know still degrades honestly.
  expect(layerKindLabel("DIARIZATION")).toContain("does not know");
  expect(layerKindNoun("DIARIZATION")).toBe("layer");
});

test("the panel never offers a verb the layer law refuses", () => {
  /*
    Transcribed from `ai_layers.ALLOWED_PARENTS`:
      PROOFREAD   -> DERIVABLE_PROSE_KINDS - {PROOFREAD}
      TRANSLATION -> DERIVABLE_PROSE_KINDS - {TRANSLATION}
      DERIVABLE_PROSE_KINDS = TEXT_KINDS - {EXPANDED}
      TEXT_KINDS = RAW_TRANSCRIPT, CLEANED_TRANSCRIPT, SUMMARY, OCR_TEXT, PROOFREAD, EXPANDED,
                   TRANSLATION, CAPTION
    Fails the moment those sets and this client's copy drift.
  */
  expect(aiVerbPlacementAllows("PROOFREAD", "RAW_TRANSCRIPT")).toBe(true);
  expect(aiVerbPlacementAllows("PROOFREAD", "TRANSLATION")).toBe(true);
  expect(aiVerbPlacementAllows("TRANSLATION", "CLEANED_TRANSCRIPT")).toBe(true);
  expect(aiVerbPlacementAllows("TRANSLATION", "PROOFREAD")).toBe(true);
  expect(aiVerbPlacementAllows("PROOFREAD", "CAPTION")).toBe(true);

  // A proofread of a proofread: the second run cannot tell a correction it is making from one the
  // first run already made.
  expect(aiVerbPlacementAllows("PROOFREAD", "PROOFREAD")).toBe(false);
  // A translation of a translation: pivot translation compounds error invisibly.
  expect(aiVerbPlacementAllows("TRANSLATION", "TRANSLATION")).toBe(false);
  // NOTHING stands on an expansion.
  expect(aiVerbPlacementAllows("PROOFREAD", "EXPANDED")).toBe(false);
  expect(aiVerbPlacementAllows("TRANSLATION", "EXPANDED")).toBe(false);
  // SUBTITLES is not a TEXT_KIND at all, so it is not a derivable parent either.
  expect(aiVerbPlacementAllows("PROOFREAD", "SUBTITLES")).toBe(false);
  // And a kind this build has never heard of fails closed in both positions.
  expect(aiVerbPlacementAllows("PROOFREAD", "DIARIZATION")).toBe(false);
  expect(aiVerbPlacementAllows("DIARIZATION", "RAW_TRANSCRIPT")).toBe(false);
});

/* ────────────────────────────────────────────────────────────────────────────
 * The ceiling
 * ──────────────────────────────────────────────────────────────────────────── */

test("0 remaining and no ceiling never look alike", () => {
  /*
    `ai_verb_cap.allowance_payload` sends BOTH numbers as null when there is no cap, and says why:
    "0 remaining and 'no ceiling' must not look alike". The obvious `?? 0` an implementer reaches for
    turns an uncapped deployment into one that refuses every run — and the guard is the one
    `Dictation.tsx` already applies to its own meter (`dictationsRemaining !== null`).
  */
  const uncapped: DwAiVerbAllowance = {
    aiVerbsLimit: null,
    aiVerbsUsed: 12,
    aiVerbsRemaining: null,
    aiVerbDay: "2026-08-19",
    aiVerbsByVerb: {}
  };
  expect(aiVerbsSpent(uncapped)).toBe(false);

  const spent: DwAiVerbAllowance = { ...uncapped, aiVerbsLimit: 12, aiVerbsRemaining: 0 };
  expect(aiVerbsSpent(spent)).toBe(true);

  const room: DwAiVerbAllowance = { ...uncapped, aiVerbsLimit: 40, aiVerbsRemaining: 34 };
  expect(aiVerbsSpent(room)).toBe(false);

  // No answer at all — the pre-flight route does not exist on this deployment, or there is no
  // connection. A client that withheld the capability here would take it away on exactly the
  // deployments that have no ceiling.
  expect(aiVerbsSpent(null)).toBe(false);
});

/* ────────────────────────────────────────────────────────────────────────────
 * Subtitles
 * ──────────────────────────────────────────────────────────────────────────── */

test("the speaker tick is offered only when the cues carry speakers", () => {
  /*
    Without this the client offers a control whose only outcome is the 422 "These subtitles carry no
    speaker labels, so a file with them in would be the same file without" — `AiLayersPanel`'s rule 3
    broken by the surface that states it.

    The payload is `subtitles.cues_payload`'s shape, and `speaker`/`estimated` are written only when
    they say something: a key present on every cue in a list of two thousand is kilobytes of `false`
    travelling to a handset on one bar of signal.
  */
  const anonymous = subtitleCueSummary({
    schema: "dw.subtitles/1",
    language: "hi",
    count: 3,
    estimatedCues: 0,
    durationSeconds: 12.5,
    cues: [
      { start: 0, end: 3.2, text: "The dabu paste is mixed" },
      { start: 3.2, end: 7.9, text: "with gum and clay" },
      { start: 7.9, end: 12.5, text: "and left overnight" }
    ]
  });
  expect(anonymous.hasSpeakers).toBe(false);
  expect(anonymous.count).toBe(3);
  expect(anonymous.estimatedCues).toBe(0);
  expect(anonymous.language).toBe("hi");
  expect(anonymous.readable).toBe(true);

  const diarized = subtitleCueSummary({
    schema: "dw.subtitles/1",
    language: null,
    count: 2,
    estimatedCues: 1,
    durationSeconds: 6,
    cues: [
      { start: 0, end: 3, text: "Tell me how it is prepared", speaker: "Speaker 1" },
      { start: 3, end: 6, text: "With gum and clay", speaker: "Speaker 2", estimated: true }
    ]
  });
  expect(diarized.hasSpeakers).toBe(true);
  // Read from the stored count rather than recomputed: the server stores it so a list can say "142
  // cues, 11 of them approximate" without carrying every cue.
  expect(diarized.estimatedCues).toBe(1);
  expect(diarized.language).toBeNull();

  // A payload that is not a cue list at all answers "nothing to show" rather than an empty file.
  const nonsense = subtitleCueSummary({ selfReportedConfidence: 0.8 });
  expect(nonsense.readable).toBe(false);
  expect(nonsense.hasSpeakers).toBe(false);
  expect(nonsense.count).toBe(0);
});

/* ────────────────────────────────────────────────────────────────────────────
 * The passage under the caret
 * ──────────────────────────────────────────────────────────────────────────── */

test("the selected passage is what is sent, and its bound is stated before the press", () => {
  const doc = fromPlainText("First paragraph.\nSecond paragraph.\nThird paragraph.");
  const whole: DocRange = { anchor: { block: 0, offset: 0 }, focus: { block: 2, offset: 17 } };
  expect(selectedPassage(doc, whole)).toBe("First paragraph.\nSecond paragraph.\nThird paragraph.");

  const middle: DocRange = { anchor: { block: 1, offset: 0 }, focus: { block: 1, offset: 6 } };
  expect(selectedPassage(doc, middle)).toBe("Second");

  // Backwards selections are the ordinary case on a drag up the page and must read the same.
  const backwards: DocRange = { anchor: { block: 1, offset: 6 }, focus: { block: 1, offset: 0 } };
  expect(selectedPassage(doc, backwards)).toBe("Second");

  const collapsed: DocRange = { anchor: { block: 1, offset: 3 }, focus: { block: 1, offset: 3 } };
  expect(selectedPassage(doc, collapsed)).toBe("");

  // The client's bound is `ai_layers.MAX_SOURCE_TEXT_CHARS`, kept in step by hand. A field can hold
  // ten times this (`MAX_DOCUMENT_CHARS` is 200,000), which is exactly why the control is
  // selection-scoped rather than field-scoped.
  expect(MAX_VERB_TEXT_CHARS).toBe(20_000);
});

test("a translation into 'multi' is refused before the press, with the reason rather than a regex", () => {
  // `_check_languages` refuses a translation INTO `multi` because a target language is a CHOICE the
  // caller makes and not an observation — while `multi` stays a perfectly real SOURCE language,
  // since these interviews code-switch mid-sentence.
  expect(translationTargetRefusal("multi")).toContain("not something a translation can be INTO");
  expect(translationTargetRefusal("MULTI")).toContain("not something a translation can be INTO");
  expect(translationTargetRefusal("Odia")).toBeNull();
  expect(translationTargetRefusal("or")).toBeNull();
  expect(translationTargetRefusal("")).toContain("Name the language");
  expect(translationTargetRefusal("x".repeat(41))).toContain("at most 40 characters");
});

/* ────────────────────────────────────────────────────────────────────────────
 * The prerequisite: the artisan's answer, recorded where there is no signal
 * ──────────────────────────────────────────────────────────────────────────── */

test("a consent recorded in a courtyard survives the next server read", () => {
  /*
    THE PREREQUISITE THIS WHOLE FEATURE RESTS ON. Every verb and every server dictation is refused
    with a 409 ending "Open the workshop's own screen and record the artisan's answer to that
    question" — and the web had no such screen and did not even decode the column. `recordDraftConsent`
    writes the answer on the device first, because the artisan is standing there and the courtyard
    has no signal; this is what stops the next background read of the workshop throwing it away.
  */
  const unsent = {
    decision: "GRANTED",
    recordedAt: "2026-08-19T09:00:00.000Z",
    recordedById: "u1",
    recordedByName: "Meera Joshi",
    synced: false
  };
  const serverStillBlank = { dictationConsent: "NOT_RECORDED", dictationConsentAt: null, dictationConsentById: null };
  expect(mergeDraftConsent(unsent, serverStillBlank)).toEqual(unsent);

  // The same answer arriving back from the server — from the web, a colleague's phone, or an earlier
  // push whose response never came — is an acknowledgement, not a conflict. Marking it synced is
  // what stops this device re-pushing it on every single open.
  const echoed = mergeDraftConsent(unsent, {
    dictationConsent: "GRANTED",
    dictationConsentAt: "2026-08-19T09:00:00.000Z",
    dictationConsentById: "u1"
  });
  expect(echoed?.synced).toBe(true);

  // A LATER answer wins: an artisan who changes their mind is exactly what the consent log is for.
  const laterRefusal = mergeDraftConsent(unsent, {
    dictationConsent: "REFUSED",
    dictationConsentAt: "2026-08-20T11:00:00.000Z",
    dictationConsentById: "u2"
  });
  expect(laterRefusal?.decision).toBe("REFUSED");

  // And an EARLIER one does not.
  const earlierRefusal = mergeDraftConsent(unsent, {
    dictationConsent: "REFUSED",
    dictationConsentAt: "2026-08-18T11:00:00.000Z",
    dictationConsentById: "u2"
  });
  expect(earlierRefusal?.decision).toBe("GRANTED");
});

test("an unorderable consent disagreement fails closed", () => {
  /*
    No moment to compare — a clock this browser cannot parse, or a server that sent no timestamp.
    Guessing wrong towards REFUSED costs a designer a capability until the answer is recorded again;
    guessing wrong the other way sends an artisan's voice to a provider after they said no. Android's
    `dwConsentMerge` takes the same side, and the two clients must not disagree about it.
  */
  const unsent = {
    decision: "GRANTED",
    recordedAt: "not-a-date",
    recordedById: "u1",
    recordedByName: null,
    synced: false
  };
  const merged = mergeDraftConsent(unsent, {
    dictationConsent: "REFUSED",
    dictationConsentAt: null,
    dictationConsentById: "u2"
  });
  expect(merged?.decision).toBe("REFUSED");
});

test("the sync push refuses to invent a grant out of a token it does not recognise", () => {
  /*
    `runSync`'s consent step read `decision === "REFUSED" ? "REFUSED" : "GRANTED"`, which sends
    anything that is not literally REFUSED up as permission to send an artisan's voice to a
    third-party provider. It is unreachable today — only `recordDraftConsent` leaves a record
    `synced: false` and it is typed to the two answers — and that is an argument for correcting it
    rather than for leaving it: `DwDraftConsent.decision` is widened with `string` for the reason
    every enum on this boundary is, and the direction the old line failed in was OPEN, inside the one
    module whose whole argument (`mergeDraftConsent` case 4, one test above) is failing CLOSED.

    ASSERTED AGAINST THE SOURCE because the step is three lines inside a 200-line pass over
    IndexedDB, media blobs and the outbox; nothing here can call it. What can be pinned exactly is the
    SHAPE: both literals are named, and no expression turns a non-REFUSED value into a GRANTED one.

    THROUGH `codeWithoutComments`, and not as a courtesy — the repair's own comment QUOTES the line it
    replaced, which is the house rule (name the defect a guard exists for) and which a scan of the raw
    text reads as the defect still being present. Same reasoning as the paste-button test below: a
    token inside a comment is prose about the rule, a token in code is the rule being broken.
  */
  const store = codeWithoutComments(readFileSync(path.join(process.cwd(), "lib/designWorkshopStore.ts"), "utf8"));
  expect(store, "the push is where this spec thinks it is").toMatch(
    /recordDesignWorkshopDictationConsent\(remoteId, answer,/
  );
  expect(store, "an unrecognised token must not be coerced into a grant").not.toMatch(/\?\s*"REFUSED"\s*:\s*"GRANTED"/);
  expect(store, "both answers must be named before anything is sent").toMatch(
    /answer === "GRANTED" \|\| answer === "REFUSED"/
  );
  // And it must still be left unsynced rather than dropped, so it stays visibly outstanding on the
  // consent card instead of being reported as sent.
  expect(store, "only a landed push may mark the record synced").toMatch(
    /if \(landed\) draft = \(await markDraftConsentSynced/
  );
});

/* ────────────────────────────────────────────────────────────────────────────
 * The rule that has no runtime, only an absence
 * ──────────────────────────────────────────────────────────────────────────── */

test("there is no way to write a verb's output into a field", () => {
  /*
    ══════════════════════════════════════════════════════════════════════════════════════════════
    THE SINGLE MOST IMPORTANT TEST IN THIS FILE, AND THE ONLY ONE THAT ASSERTS AN ABSENCE.
    ══════════════════════════════════════════════════════════════════════════════════════════════

    Plan §3's guarantee — no AI-produced value may feed a field compared across surfaces — is true on
    the SERVER by construction: `LayerWritePlan.__post_init__` refuses any table outside
    `WRITABLE_TABLES`, and `DwStageEntry` is deliberately absent, so a later change that opens that
    door has to delete a check, which is a visible act in a diff. On the CLIENT it is true only by
    there being nothing to call — and an absence has no runtime a test can exercise.

    So this reads the three surfaces' SOURCE, in the shape `record-form-dictation-unit.spec.ts` and
    Android's own `DwTier2LayerTest` already use. The whole point is that adding a paste button, a
    clipboard copy, or a commit into the document model becomes a FAILING TEST rather than a helpful
    commit — because it will feel like a kindness: a proofread a designer cannot put back into their
    own sentence reads like a half-finished feature. It is not. `ai_verbs.expand` states the
    alternative this repository actively prefers: "A designer who wants those words in the field
    types them, at which point they are that designer's sentences under that designer's name — which
    is a true statement, unlike anything a paste button could produce."

    `navigator.clipboard` is in the list for the reason the risk table gives: a clipboard button is a
    paste button with one extra keystroke, and the cross-surface argument does not count keystrokes.
  */
  const surfaces = [
    "components/designworkshop/AiVerbReviewDialog.tsx",
    "components/designworkshop/AiVerbSelectionMenu.tsx",
    "components/designworkshop/MediaAiVerbs.tsx"
  ];
  const forbidden = ["onChange(", "navigator.clipboard", "commit(", "document.execCommand", "StoredRichDoc"];

  for (const file of surfaces) {
    const code = codeWithoutComments(readFileSync(path.join(process.cwd(), file), "utf8"));
    for (const token of forbidden) {
      expect(code, `${file} must not contain ${token} — see AiVerbReviewDialog's header`).not.toContain(token);
    }
  }
});

/**
 * A source file with its comments taken out, so the rule can be stated in the files it governs.
 *
 * THIS IS NOT A LOOPHOLE, IT IS THE POINT. The whole mitigation for the paste button is that the
 * REASON is written in the file header — the test only catches somebody who did not read it — and
 * the header cannot explain which calls are forbidden without naming them. Scanning the raw text
 * made the three headers fail their own test. A token inside a comment is prose about the rule; a
 * token in code is the rule being broken.
 *
 * Deliberately crude: block comments removed wholesale, and whole lines that are nothing but a line
 * comment. It does NOT try to understand strings, so it would mangle a `//` inside a string literal
 * — there is none in these three files, and the failure mode is a false PASS on a token that
 * happened to sit after one, which is why the check is a floor and the header is the mitigation.
 */
function codeWithoutComments(source: string): string {
  return source
    .replace(/\/\*[\s\S]*?\*\//g, "")
    .split("\n")
    .filter((line) => !line.trim().startsWith("//"))
    .join("\n");
}

/* ────────────────────────────────────────────────────────────────────────────
 * The pre-press ladder, and the three mounts it hangs off
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * The five component files this feature's controls actually live in, read as text.
 *
 * The mounts are STRUCTURAL — a JSX element inside a ternary inside a 3,000-line editor — so no unit
 * assertion can reach them by calling anything, and the two defects that shipped here both lived in
 * exactly that layer. Reading the source is the same instrument `record-form-dictation-unit.spec.ts`
 * uses for the dictation button's own local-workshop guard, one file over.
 */
const SURFACE_FILES = {
  editor: "components/designworkshop/RichTextEditor.tsx",
  fieldInput: "components/designworkshop/FieldInput.tsx",
  selectionMenu: "components/designworkshop/AiVerbSelectionMenu.tsx",
  mediaVerbs: "components/designworkshop/MediaAiVerbs.tsx",
  layersPanel: "components/designworkshop/AiLayersPanel.tsx"
} as const;

function surfaceCode(which: keyof typeof SURFACE_FILES): string {
  return codeWithoutComments(readFileSync(path.join(process.cwd(), SURFACE_FILES[which]), "utf8"));
}

test("a workshop that exists only on this device refuses every verb, and says which fact is missing", () => {
  /*
    THE HIGH DEFECT, AS AN ASSERTION.

    All three surfaces offered their verbs on a workshop whose id is a `dwlocal-…` draft id, and every
    press produced a bare 404 from `load_workshop_or_404` — an AI action that is visibly available and
    always fails, which is worse than one that is absent because the designer cannot tell it from
    something they did wrong.

    The rest of the ladder is structurally unable to catch it, and that is the part worth pinning:
    `useWorkshopConsent` reads the LOCAL draft, and `DictationConsentCard` deliberately supports
    recording GRANTED on a workshop that has never been up ("Recorded on this device. This workshop
    has not reached the repository yet, so the answer goes up with it"). So the granted-and-unsynced
    row below is not a corner — it is the ordinary state of a workshop on the second day of a
    fortnight in the field, and it is the row the old code answered `null` (go ahead) for.
  */
  const granted = { ready: true, decision: "GRANTED" };

  expect(
    verbWorkshopRefusal({ ...granted, serverId: null }),
    "consent granted in a courtyard, workshop never synced — the exact shipped defect"
  ).toBe(WORKSHOP_NOT_ON_SERVER_YET);

  expect(verbWorkshopRefusal({ ...granted, serverId: "cmworkshop0000000000000a" }), "the ordinary case").toBeNull();

  // AND THE ORDER, which is the half that is easy to get subtly wrong. The sync sentence promises the
  // verbs "become available" after the next sync; on a workshop whose recorded answer is REFUSED they
  // will not, so the consent rung has to be reached first. Every combination gets a true sentence.
  for (const decision of ["REFUSED", "NOT_RECORDED"]) {
    expect(
      verbWorkshopRefusal({ ready: true, decision, serverId: null }),
      `${decision} and unsynced must NOT promise that syncing makes the verbs available`
    ).toBe(consentNotGranted(decision));
  }

  // A token from a server one release ahead is not GRANTED, so it closes the gate — the same
  // fail-closed direction `dictation_consent.consent_of` takes.
  expect(verbWorkshopRefusal({ ready: true, decision: "PENDING_REVIEW", serverId: "cmw1" })).toBe(
    consentNotGranted("PENDING_REVIEW")
  );
});

test("the still-reading state is a refusal with nothing to say, and truthiness reads it as consent", () => {
  /*
    THE SECOND DEFECT, AND THE REASON THE ANSWER IS THREE-VALUED RATHER THAN A BOOLEAN OR A STRING.

    While the IndexedDB read is in flight the ladder answers "" — inert, but SILENT, because the floor
    answer is NOT_RECORDED and drawing its sentence would flash "nobody has been asked" on every
    workshop that has been asked. `AiLayersPanel`'s `LayerVerbs` fed that value into a truthiness
    ternary and the empty string is falsy, so during the read it rendered live, pressable Proofread
    and Translate buttons — which on a NOT_RECORDED workshop is the 409 the whole ladder exists to
    prevent. The other two surfaces got inertness for free by disabling on a comparison against null.

    So the property is: "" is NOT null, and every consumer tests `!== null`.
  */
  const reading = verbWorkshopRefusal({ ready: false, serverId: null, decision: "NOT_RECORDED" });
  expect(reading, "silent").toBe("");
  expect(reading, "but still a refusal").not.toBeNull();
  expect(Boolean(reading), "and this is exactly why truthiness is the wrong test").toBe(false);

  // `ready: false` outranks everything below it — a control must not refuse on a value it has not read.
  expect(verbWorkshopRefusal({ ready: false, serverId: "cmw1", decision: "REFUSED" })).toBe("");

  /*
    AND THE THREE SURFACES ALL TEST IT THE RIGHT WAY — which is TWO different right ways, and telling
    them apart is the whole defect rather than a nicety:

      - WHAT TO DRAW may test truthiness. `{blocked ? <p>{blocked}</p> : null}` is precisely how ""
        becomes silence, and it is correct where the two field surfaces use it.
      - WHETHER A CONTROL IS LIVE may NOT. That has to be `!== null`, because "" is a refusal that
        happens to have nothing to say.

    `LayerVerbs` used ONE ternary for both jobs — the sentence in the then-arm and the BUTTONS in the
    else-arm — so the same truthiness that gave it silence also gave it live buttons for the length of
    the IndexedDB read. Its branch is therefore asserted by shape below, because that is the line that
    was wrong; forbidding truthiness everywhere would forbid the correct use as well.
  */
  for (const which of ["selectionMenu", "mediaVerbs", "layersPanel"] as const) {
    expect(
      surfaceCode(which),
      `${SURFACE_FILES[which]} must gate its controls on a comparison, not on truthiness`
    ).toMatch(/blocked !== null/);
  }
  const panel = surfaceCode("layersPanel");
  expect(panel, "LayerVerbs' sentence-or-buttons branch must not be a truthiness test").not.toMatch(
    /\) : blocked \? \(/
  );
  expect(panel, "and must be the null comparison").toMatch(/\) : blocked !== null \? \(/);
  // The panel-wide value the same rule governs, in case somebody reintroduces the ternary upstream.
  expect(panel).not.toMatch(/verbsBlocked \?/);
});

test("no surface sends the URL's workshop id to the server, because it is not the server's id", () => {
  /*
    THE ROUTE PARAM IS A `dwlocal-…` DRAFT ID FOR THE WHOLE LIFE OF A DRAFT, AND STAYS ONE AFTER SYNC
    because the stage page does not redirect. `useWorkshopConsent` resolves `draft.remoteId` off the
    read it is already doing, using the same expression `reportTarget.ts` and the stage page use, and
    every call below goes through THAT.

    This is the assertion that would have caught the shipped defect at review time: not "is there a
    guard" but "does the id that goes on the wire come from the draft". A verb call taking `workshopId`
    is the bug, whatever guard stands above it.
  */
  const verbCalls =
    /(?:proofread|expand|translate|caption|subtitle)[A-Za-z]*\(\s*workshopId\b|listDesignWorkshopAiLayers\(\s*workshopId\b/;

  for (const which of ["selectionMenu", "mediaVerbs"] as const) {
    const code = surfaceCode(which);
    expect(code, `${SURFACE_FILES[which]} sends the route param to a verb route`).not.toMatch(verbCalls);
    expect(code, `${SURFACE_FILES[which]} must resolve the server's id`).toMatch(/consent\.serverId/);
    expect(code, `${SURFACE_FILES[which]} must consult the shared ladder`).toMatch(/verbWorkshopRefusal\(/);
    // Accept, decline and the subtitle download are server routes too, so the dialog gets the same id.
    expect(code, `${SURFACE_FILES[which]} hands the review dialog the route param`).toMatch(
      /workshopId=\{serverId \?\? workshopId\}/
    );
  }

  // AND THE HOOK IS WHERE THE RESOLUTION LIVES, so the three surfaces cannot drift into three answers.
  const hook = codeWithoutComments(
    readFileSync(path.join(process.cwd(), "components/hooks/useWorkshopConsent.ts"), "utf8")
  );
  expect(hook, "the same expression reportTarget.ts uses").toMatch(
    /serverId: draft\?\.remoteId \?\? \(isLocalWorkshopId\(workshopId\) \? null : workshopId\)/
  );
});

test("the three mounts are still there, so the verbs cannot be lost by a refactor", () => {
  // The disclosed gap this file used to name: there was no assertion of any kind over the mounts, and
  // that is precisely the layer the local-workshop defect lived in. A verb menu silently dropped from
  // the toolbar is invisible in a diff of 3,000 lines.
  expect(surfaceCode("editor"), "the editor must still mount the verb menu").toMatch(/<AiVerbSelectionMenu\b/);
  expect(surfaceCode("editor"), "and still hand it the workshop it is drawn in").toMatch(
    /<AiVerbSelectionMenu[\s\S]{0,200}?workshopId=\{workshopId\}/
  );
  expect(surfaceCode("fieldInput"), "the stage form must still mount the media verbs").toMatch(
    /<MediaAiVerbs[^>]*workshopId=\{workshopId\}/
  );
});

test("one sentence for a spent ceiling, not one per surface", () => {
  /*
    The three surfaces each carried their own copy of the fallback ceiling refusal — the same forty
    words, three times — which is the drift this module's header forbids in as many words: *"the only
    strings either client authors are these, and they are transliterated rather than reworded"*.
  */
  const spent: DwAiVerbAllowanceState = {
    aiVerbsLimit: 40,
    aiVerbsUsed: 40,
    aiVerbsRemaining: 0,
    aiVerbDay: "2026-08-19",
    aiVerbsByVerb: {},
    refusal: null
  };
  const fallback = verbAllowanceRefusal(spent);
  expect(fallback, "the client's own words, only where the server sent none").toContain("used up on this account");

  // THE SERVER'S SENTENCE WINS WHEREVER IT SENT ONE — a rule with two voices is how a client and a
  // server come to disagree about what a refusal means.
  expect(verbAllowanceRefusal({ ...spent, refusal: "Nine runs a day on this deployment." })).toBe(
    "Nine runs a day on this deployment."
  );

  // NO CEILING IS NOT A SPENT CEILING, which is the `?? 0` trap one test above already guards for the
  // countdown; here it must not produce a refusal at all.
  expect(verbAllowanceRefusal({ ...spent, aiVerbsLimit: null, aiVerbsRemaining: null })).toBeNull();
  expect(verbAllowanceRefusal(null), "no pre-flight route, no connection — not a refusal").toBeNull();

  for (const which of ["selectionMenu", "mediaVerbs", "layersPanel"] as const) {
    // Two assertions rather than one, because the `not.toContain` alone only catches a COPY of these
    // exact words — a surface that invented its own wording for the ceiling would slip past it, and
    // an invented wording is the worse of the two failures.
    expect(surfaceCode(which), `${SURFACE_FILES[which]} carries its own copy of the ceiling sentence`).not.toContain(
      "used up on this account"
    );
    expect(surfaceCode(which), `${SURFACE_FILES[which]} must read the ceiling through the shared function`).toMatch(
      /verbAllowanceRefusal\(/
    );
  }
});

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

const VERB_201 = JSON.parse(`
{
  "layer": {
    "id": "cmlayer00000000000proofread",
    "designWorkshopId": "cmworkshop0000000000000a",
    "kind": "PROOFREAD",
    "tier": "TIER_3",
    "source": { "kind": "SUPPLIED_TEXT", "id": null, "text": "warp sized w/ rice paste, 2 dips" },
    "provider": "OPENAI",
    "modelId": "gpt-6-luna",
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

type Sent = { url: string; body: Record<string, unknown> };

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
  const expanded = await capture(() => expandDesignWorkshopNote("w1", "warp sized w/ rice paste"));
  expect(Object.keys(expanded.body)).toEqual(["text"]);
  expect(expanded.body).not.toHaveProperty("sourceLayerId");
  expect(expanded.url).toContain("/ai-layers/expand");

  const proofreadText = await capture(() => proofreadDesignWorkshopText("w1", { text: "a note" }));
  expect(proofreadText.body).toEqual({ text: "a note" });

  const proofreadLayer = await capture(() => proofreadDesignWorkshopText("w1", { sourceLayerId: "L1" }));
  expect(proofreadLayer.body).toEqual({ sourceLayerId: "L1" });

  const translated = await capture(() => translateDesignWorkshopText("w1", { sourceLayerId: "L1" }, "Odia"));
  expect(translated.body).toEqual({ sourceLayerId: "L1", targetLanguage: "Odia" });
  expect("text" in translated.body).toBe(false);
});

test("every key of the 201 is read, including the two the client never declared", async () => {
  let decoded: Awaited<ReturnType<typeof proofreadDesignWorkshopText>> | null = null;
  await capture(async () => {
    decoded = await proofreadDesignWorkshopText("w1", { text: "a note" });
  });
  const answer = decoded as unknown as Record<string, unknown> & { layer: Record<string, unknown> };

  expect(answer.accepted).toBe(false);
  expect(answer.acceptanceRequired).toBe(true);

  expect(answer.aiVerbsLimit).toBe(40);
  expect(answer.aiVerbsUsed).toBe(6);
  expect(answer.aiVerbsRemaining).toBe(34);
  expect(answer.aiVerbDay).toBe("2026-08-19");
  expect(answer.aiVerbsByVerb).toEqual({ PROOFREAD: 4, CAPTION: 2 });

  expect(layerLanguagePair(answer.layer as unknown as DwAiLayer)).toBeNull();
  expect(
    layerLanguagePair(layerOf({ sourceLanguage: "multi", targetLanguage: "English" }))
  ).toEqual({ from: "multi — mixed, code-switched speech", into: "English" });
  expect(layerLanguagePair(layerOf({ sourceLanguage: "UNRECORDED", targetLanguage: "Odia" }))).toEqual({
    from: "not recorded",
    into: "Odia"
  });

  expect((answer.layer.source as Record<string, unknown>).text).toBe("warp sized w/ rice paste, 2 dips");
  expect(answer.layer.text).toBe("Warp sized with rice paste, two dips.");
});

test("a media verb sends the media id, and subtitles cannot be asked for in a language", async () => {
  const caption = await capture(() => captionDesignWorkshopMedia("w1", "m1"));
  expect(caption.body).toEqual({ sourceMediaId: "m1" });
  expect(caption.url).toContain("/ai-layers/caption");

  const subtitles = await capture(() => subtitleDesignWorkshopMedia("w1", "m1"));
  expect(subtitles.body).toEqual({ sourceMediaId: "m1" });
  expect(subtitles.url).toContain("/ai-layers/subtitles");
});

test("a supplied-text layer is a root of its own and is never reported as an unknown source", () => {
  const grouping = groupAiLayers([VERB_201.layer as DwAiLayer]);

  expect(grouping.notes).toHaveLength(1);
  expect(grouping.notes[0].layer.id).toBe("cmlayer00000000000proofread");
  expect(grouping.unrooted).toEqual([]);
  expect(grouping.recordings).toEqual([]);
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
  const expected: Record<string, string> = {
    PROOFREAD: "AI-corrected spelling and punctuation",
    EXPANDED: "Prose written by AI from a designer's note",
    TRANSLATION: "AI translation",
    CAPTION: "AI description of a photograph or video",
    SUBTITLES: "AI subtitles, with their timings"
  };
  for (const [kind, heading] of Object.entries(expected)) {
    expect(layerKindLabel(kind)).toBe(heading);
    expect(layerKindLabel(kind)).not.toContain("unfamiliar");
    expect(layerKindNote(kind)).toBeTruthy();
    const sentence = `Accept this ${layerKindNoun(kind)} in your name?`;
    expect(sentence).not.toContain("unfamiliar");
    expect(sentence).not.toContain("layer kind");
  }

  expect(layerKindNote("EXPANDED")).toContain("was not recorded in the field");

  expect(layerKindLabel("DIARIZATION")).toContain("unfamiliar");
  expect(layerKindNoun("DIARIZATION")).toBe("layer");
});

test("the panel never offers a verb the layer law refuses", () => {
  expect(aiVerbPlacementAllows("PROOFREAD", "RAW_TRANSCRIPT")).toBe(true);
  expect(aiVerbPlacementAllows("PROOFREAD", "TRANSLATION")).toBe(true);
  expect(aiVerbPlacementAllows("TRANSLATION", "CLEANED_TRANSCRIPT")).toBe(true);
  expect(aiVerbPlacementAllows("TRANSLATION", "PROOFREAD")).toBe(true);
  expect(aiVerbPlacementAllows("PROOFREAD", "CAPTION")).toBe(true);

  expect(aiVerbPlacementAllows("PROOFREAD", "PROOFREAD")).toBe(false);
  expect(aiVerbPlacementAllows("TRANSLATION", "TRANSLATION")).toBe(false);
  expect(aiVerbPlacementAllows("PROOFREAD", "EXPANDED")).toBe(false);
  expect(aiVerbPlacementAllows("TRANSLATION", "EXPANDED")).toBe(false);
  expect(aiVerbPlacementAllows("PROOFREAD", "SUBTITLES")).toBe(false);
  expect(aiVerbPlacementAllows("PROOFREAD", "DIARIZATION")).toBe(false);
  expect(aiVerbPlacementAllows("DIARIZATION", "RAW_TRANSCRIPT")).toBe(false);
});

test("0 remaining and no ceiling never look alike", () => {
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

  expect(aiVerbsSpent(null)).toBe(false);
});

test("the speaker tick is offered only when the cues carry speakers", () => {
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
  expect(diarized.estimatedCues).toBe(1);
  expect(diarized.language).toBeNull();

  const nonsense = subtitleCueSummary({ selfReportedConfidence: 0.8 });
  expect(nonsense.readable).toBe(false);
  expect(nonsense.hasSpeakers).toBe(false);
  expect(nonsense.count).toBe(0);
});

test("the selected passage is what is sent, and its bound is stated before the press", () => {
  const doc = fromPlainText("First paragraph.\nSecond paragraph.\nThird paragraph.");
  const whole: DocRange = { anchor: { block: 0, offset: 0 }, focus: { block: 2, offset: 17 } };
  expect(selectedPassage(doc, whole)).toBe("First paragraph.\nSecond paragraph.\nThird paragraph.");

  const middle: DocRange = { anchor: { block: 1, offset: 0 }, focus: { block: 1, offset: 6 } };
  expect(selectedPassage(doc, middle)).toBe("Second");

  const backwards: DocRange = { anchor: { block: 1, offset: 6 }, focus: { block: 1, offset: 0 } };
  expect(selectedPassage(doc, backwards)).toBe("Second");

  const collapsed: DocRange = { anchor: { block: 1, offset: 3 }, focus: { block: 1, offset: 3 } };
  expect(selectedPassage(doc, collapsed)).toBe("");

  expect(MAX_VERB_TEXT_CHARS).toBe(20_000);
});

test("a translation into 'multi' is refused before the press, with the reason rather than a regex", () => {
  expect(translationTargetRefusal("multi")).toContain("not the language to translate into");
  expect(translationTargetRefusal("MULTI")).toContain("not the language to translate into");
  expect(translationTargetRefusal("Odia")).toBeNull();
  expect(translationTargetRefusal("or")).toBeNull();
  expect(translationTargetRefusal("")).toContain("Name the language");
  expect(translationTargetRefusal("x".repeat(41))).toContain("at most 40 characters");
});

test("a consent recorded in a courtyard survives the next server read", () => {
  const unsent = {
    decision: "GRANTED",
    recordedAt: "2026-08-19T09:00:00.000Z",
    recordedById: "u1",
    recordedByName: "Meera Joshi",
    synced: false
  };
  const serverStillBlank = { dictationConsent: "NOT_RECORDED", dictationConsentAt: null, dictationConsentById: null };
  expect(mergeDraftConsent(unsent, serverStillBlank)).toEqual(unsent);

  const echoed = mergeDraftConsent(unsent, {
    dictationConsent: "GRANTED",
    dictationConsentAt: "2026-08-19T09:00:00.000Z",
    dictationConsentById: "u1"
  });
  expect(echoed?.synced).toBe(true);

  const laterRefusal = mergeDraftConsent(unsent, {
    dictationConsent: "REFUSED",
    dictationConsentAt: "2026-08-20T11:00:00.000Z",
    dictationConsentById: "u2"
  });
  expect(laterRefusal?.decision).toBe("REFUSED");

  const earlierRefusal = mergeDraftConsent(unsent, {
    dictationConsent: "REFUSED",
    dictationConsentAt: "2026-08-18T11:00:00.000Z",
    dictationConsentById: "u2"
  });
  expect(earlierRefusal?.decision).toBe("GRANTED");
});

test("an unorderable consent disagreement fails closed", () => {
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
  const store = codeWithoutComments(readFileSync(path.join(process.cwd(), "lib/designWorkshopStore.ts"), "utf8"));
  expect(store, "the push is where this spec thinks it is").toMatch(
    /recordDesignWorkshopDictationConsent\(remoteId, answer,/
  );
  expect(store, "an unrecognised token must not be coerced into a grant").not.toMatch(/\?\s*"REFUSED"\s*:\s*"GRANTED"/);
  expect(store, "both answers must be named before anything is sent").toMatch(
    /answer === "GRANTED" \|\| answer === "REFUSED"/
  );
  expect(store, "only a landed push may mark the record synced").toMatch(
    /if \(landed\) draft = \(await markDraftConsentSynced/
  );
});

test("there is no way to write a verb's output into a field", () => {
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

function codeWithoutComments(source: string): string {
  return source
    .replace(/\/\*[\s\S]*?\*\//g, "")
    .split("\n")
    .filter((line) => !line.trim().startsWith("//"))
    .join("\n");
}

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
  const granted = { ready: true, decision: "GRANTED" };

  expect(
    verbWorkshopRefusal({ ...granted, serverId: null }),
    "consent granted in a courtyard, workshop never synced — the exact shipped defect"
  ).toBe(WORKSHOP_NOT_ON_SERVER_YET);

  expect(verbWorkshopRefusal({ ...granted, serverId: "cmworkshop0000000000000a" }), "the ordinary case").toBeNull();

  expect(
    verbWorkshopRefusal({ ready: true, decision: "REFUSED", serverId: null }),
    "REFUSED and unsynced must NOT promise that syncing makes the verbs available"
  ).toBe(consentNotGranted("REFUSED"));

  expect(verbWorkshopRefusal({ ready: true, decision: "NOT_RECORDED", serverId: null })).toBe(WORKSHOP_NOT_ON_SERVER_YET);
  expect(verbWorkshopRefusal({ ready: true, decision: "NOT_RECORDED", serverId: "cmw1" })).toBeNull();
  expect(verbWorkshopRefusal({ ready: true, decision: "PENDING_REVIEW", serverId: "cmw1" })).toBeNull();
  expect(verbWorkshopRefusal({ ready: true, decision: "REFUSED", serverId: "cmw1" })).toBe(consentNotGranted("REFUSED"));
});

test("the still-reading state is a refusal with nothing to say, and truthiness reads it as consent", () => {
  const reading = verbWorkshopRefusal({ ready: false, serverId: null, decision: "NOT_RECORDED" });
  expect(reading, "silent").toBe("");
  expect(reading, "but still a refusal").not.toBeNull();
  expect(Boolean(reading), "and this is exactly why truthiness is the wrong test").toBe(false);

  expect(verbWorkshopRefusal({ ready: false, serverId: "cmw1", decision: "REFUSED" })).toBe("");

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
  expect(panel).not.toMatch(/verbsBlocked \?/);
});

test("no surface sends the URL's workshop id to the server, because it is not the server's id", () => {
  const verbCalls =
    /(?:proofread|expand|translate|caption|subtitle)[A-Za-z]*\(\s*workshopId\b|listDesignWorkshopAiLayers\(\s*workshopId\b/;

  for (const which of ["selectionMenu", "mediaVerbs"] as const) {
    const code = surfaceCode(which);
    expect(code, `${SURFACE_FILES[which]} sends the route param to a verb route`).not.toMatch(verbCalls);
    expect(code, `${SURFACE_FILES[which]} must resolve the server's id`).toMatch(/consent\.serverId/);
    expect(code, `${SURFACE_FILES[which]} must consult the shared ladder`).toMatch(/verbWorkshopRefusal\(/);
    expect(code, `${SURFACE_FILES[which]} hands the review dialog the route param`).toMatch(
      /workshopId=\{serverId \?\? workshopId\}/
    );
  }

  const hook = codeWithoutComments(
    readFileSync(path.join(process.cwd(), "components/hooks/useWorkshopConsent.ts"), "utf8")
  );
  expect(hook, "the same expression reportTarget.ts uses").toMatch(
    /serverId: draft\?\.remoteId \?\? \(isLocalWorkshopId\(workshopId\) \? null : workshopId\)/
  );
});

test("the three mounts are still there, so the verbs cannot be lost by a refactor", () => {
  expect(surfaceCode("editor"), "the editor must still mount the verb menu").toMatch(/<AiVerbSelectionMenu\b/);
  expect(surfaceCode("editor"), "and still hand it the workshop it is drawn in").toMatch(
    /<AiVerbSelectionMenu[\s\S]{0,200}?workshopId=\{workshopId\}/
  );
  expect(surfaceCode("fieldInput"), "the stage form must still mount the media verbs").toMatch(
    /<MediaAiVerbs[^>]*workshopId=\{workshopId\}/
  );
});

test("one sentence for a spent ceiling, not one per surface", () => {
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

  expect(verbAllowanceRefusal({ ...spent, refusal: "Nine runs a day on this deployment." })).toBe(
    "Nine runs a day on this deployment."
  );

  expect(verbAllowanceRefusal({ ...spent, aiVerbsLimit: null, aiVerbsRemaining: null })).toBeNull();
  expect(verbAllowanceRefusal(null), "no pre-flight route, no connection — not a refusal").toBeNull();

  for (const which of ["selectionMenu", "mediaVerbs", "layersPanel"] as const) {
    expect(surfaceCode(which), `${SURFACE_FILES[which]} carries its own copy of the ceiling sentence`).not.toContain(
      "used up on this account"
    );
    expect(surfaceCode(which), `${SURFACE_FILES[which]} must read the ceiling through the shared function`).toMatch(
      /verbAllowanceRefusal\(/
    );
  }
});

/**
 * Running one parity case. **This is the code BOTH halves execute.**
 *
 * It imports the engine and `traceRecord.ts` and nothing else — no `node:`, no DOM, no fetch. That is
 * the whole point: if the web half and the Android half ran two different sequences of engine calls,
 * a difference in the sequence would be indistinguishable from a difference in the engine, and the
 * harness would be measuring itself.
 *
 * ────────────────────────────────────────────────────────────────────────────
 * WHY THIS FILE NAMES `engine/` DIRECTLY, WHICH `lib/trace/README.md` §3 ASKS NOBODY TO DO
 * ────────────────────────────────────────────────────────────────────────────
 *
 * That section says "Import `traceClient.ts` and nothing else", and the reason it gives is a bundle
 * one: a component that reaches past the client puts 44 KB of engine on a page's graph. It is right,
 * and this file is not a component — but the exception should be stated rather than assumed, because
 * the same sentence also claims `engine/` is "named in exactly one file: `e2e/trace-engine-unit.spec.ts`",
 * and that claim is already out of date (`components/sketches/upload/traceRuntime.ts` reaches
 * `engine/styles` and `engine/subjects` through a dynamic import, and says so in its own header).
 *
 * The parity harness cannot go through `traceClient` even if it wanted to. The client's job is to
 * talk to a Web Worker; Node has no `Worker` and an Android JS host may not have one either, and
 * `worker/trace.worker.ts` refuses to load outside one. What is being compared is the ARITHMETIC, and
 * `Pipeline.run` is where the arithmetic is. The transfer protocol around it is already covered, by
 * the three `FakeWorker` cases in `e2e/trace-engine-unit.spec.ts`.
 */

import { Pipeline, RgbaImage, SvgWriter, sanitizeTraceParams } from "@/lib/trace/engine";
import type { TraceParams, TraceParamsInput } from "@/lib/trace/engine";

import { buildTraceRecord } from "./traceRecord";
import type { TraceRecord } from "./traceRecord";

export interface RunParityCaseInput {
  readonly caseId: string;
  readonly runtime: string;
  /** RGBA8 in `ImageData` byte order — exactly the `.rgba.gz` payload, undecoded and unresampled. */
  readonly pixels: Uint8ClampedArray;
  readonly width: number;
  readonly height: number;
  readonly inputSha256: string;
  readonly engineManifestSha256: string;
  /** The partial tree from `params.json`. Sanitised here, on both sides, from the same literal. */
  readonly params: TraceParamsInput;
  /** Non-null runs `Pipeline.runPreview` at this long edge instead of `Pipeline.run`. */
  readonly previewLongEdge: number | null;
}

export interface RunParityCaseOutput {
  readonly record: TraceRecord;
  /** The sanitised tree the trace actually ran with, so a caller can assert it is what it asked for. */
  readonly params: TraceParams;
  /** Kept out of the record on purpose — see `traceRecord.ts`. Useful to a caller that wants to log it. */
  readonly totalMillis: number;
}

/**
 * Traces one fixture and returns its {@link TraceRecord}.
 *
 * `sanitizeTraceParams` is called HERE rather than by the caller so that both halves normalise the
 * same partial literal through the same code. The engine would sanitise internally anyway
 * (`pipeline.run`'s first line); doing it out here as well is what lets the caller see the complete
 * tree and assert that `"OUTLINE"` really became `VectorModeParam.OUTLINE` — `enumOf` answers an
 * unrecognised string with the documented default instead of throwing, so a typo in `params.json`
 * would otherwise trace something else in silence.
 */
export async function runParityCase(input: RunParityCaseInput): Promise<RunParityCaseOutput> {
  const image = RgbaImage.fromImageData({
    data: input.pixels,
    width: input.width,
    height: input.height
  });
  const params = sanitizeTraceParams(input.params);

  const result =
    input.previewLongEdge === null
      ? await Pipeline.run(image, params)
      : await Pipeline.runPreview(image, params, input.previewLongEdge);

  /*
   * TIER 2 IS THE ENGINE'S OWN WRITER, NOT THE PRODUCT'S.
   *
   * The file that reaches `sketch.lineArtFile` is written by
   * `components/sketches/upload/geometryToSvg.ts` `buildSvg`, and that is the string a ministry
   * eventually looks at. `SvgWriter.write` is used here instead, for three reasons and one check:
   *
   *  1. It is VENDORED, so it is pinned by `UPSTREAM-MANIFEST.txt` and cannot move under a committed
   *     reference record. `geometryToSvg.ts` belongs to the upload feature and is edited often; a
   *     reference corpus that went red every time somebody touched a component would be switched off.
   *  2. It is the tier the UPSTREAM already compares exactly — `engine/bezierFit.ts:583-589` calls
   *     `toD` "a §14 string stage compared **exactly**" and `Math.fround`s every emitted control
   *     point so that two engines print the same digits. Comparing the same stage means inheriting
   *     that work rather than re-deriving it.
   *  3. Whatever runtime the handset ends up using, if it can run the engine it has this writer.
   *     `buildSvg` would have to be shipped alongside.
   *
   * The check, so that (1) is a choice and not a blind spot: `trace-parity-unit.spec.ts` asserts that
   * `buildSvg` and `SvgWriter.write` emit the same `d` string for the same shape. Two writers is one
   * more than the right number — `geometryToSvg.ts`'s own header says so — and this is the assertion
   * that would notice them drifting apart.
   *
   * Both write at `precision: 2`: `DEFAULT_SVG_OPTIONS.precision` and `buildSvg`'s `?? 2`.
   */
  const svg = SvgWriter.write(result.document);

  const record = buildTraceRecord({
    caseId: input.caseId,
    runtime: input.runtime,
    inputSha256: input.inputSha256,
    inputWidth: input.width,
    inputHeight: input.height,
    engineManifestSha256: input.engineManifestSha256,
    preview: input.previewLongEdge !== null,
    result,
    svg
  });

  return { record, params, totalMillis: result.totalMillis };
}

import { Pipeline, RgbaImage, SvgWriter, sanitizeTraceParams } from "@/lib/trace/engine";
import type { TraceParams, TraceParamsInput } from "@/lib/trace/engine";

import { buildTraceRecord } from "./traceRecord";
import type { TraceRecord } from "./traceRecord";

export interface RunParityCaseInput {
  readonly caseId: string;
  readonly runtime: string;
  readonly pixels: Uint8ClampedArray;
  readonly width: number;
  readonly height: number;
  readonly inputSha256: string;
  readonly engineManifestSha256: string;
  readonly params: TraceParamsInput;
  readonly previewLongEdge: number | null;
}

export interface RunParityCaseOutput {
  readonly record: TraceRecord;
  readonly params: TraceParams;
  readonly totalMillis: number;
}

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

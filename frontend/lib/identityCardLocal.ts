type TextDetectorLike = {
  detect(source: CanvasImageSource | ImageBitmap | Blob): Promise<{ rawValue: string }[]>;
};
type TextDetectorConstructor = { new (): TextDetectorLike };

function constructorIfAny(): TextDetectorConstructor | undefined {
  return (globalThis as unknown as { TextDetector?: TextDetectorConstructor }).TextDetector;
}

let probe: Promise<boolean> | null = null;

export function browserCanReadCards(): Promise<boolean> {
  if (probe) return probe;
  probe = (async () => {
    const Detector = constructorIfAny();
    if (!Detector || typeof createImageBitmap !== "function" || typeof ImageData !== "function") {
      return false;
    }
    let bitmap: ImageBitmap | null = null;
    try {
      bitmap = await createImageBitmap(new ImageData(8, 8));
      await new Detector().detect(bitmap);
      return true;
    } catch {
      return false;
    } finally {
      bitmap?.close();
    }
  })();
  return probe;
}

export type LocalReadFailure =
  | { reason: "unsupported" }
  | { reason: "undecodable"; filename: string }
  | { reason: "failed"; message: string };

export type LocalReadOutcome = { ok: true; text: string } | ({ ok: false } & LocalReadFailure);

export async function readCardTextInBrowser(file: File): Promise<LocalReadOutcome> {
  const Detector = constructorIfAny();
  if (!Detector) return { ok: false, reason: "unsupported" };

  let bitmap: ImageBitmap | null = null;
  try {
    try {
      bitmap = await createImageBitmap(file, { imageOrientation: "from-image" });
    } catch {
      return { ok: false, reason: "undecodable", filename: file.name };
    }
    const found = await new Detector().detect(bitmap);
    return { ok: true, text: found.map((block) => block.rawValue).join("\n") };
  } catch (error) {
    return { ok: false, reason: "failed", message: error instanceof Error ? error.message : "unknown" };
  } finally {
    bitmap?.close();
  }
}

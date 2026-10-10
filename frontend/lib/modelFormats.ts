/**
 * Which stored files are 3D models, and which format each one is. PURE — no three.js, no DOM — so a
 * unit spec can pin it and the report wording, the web viewer and the handset's viewer all answer
 * the same question the same way.
 *
 * THE LIST IS THE UPLOAD CARD'S. `components/sketches/upload/PrototypeModelField.tsx` offers exactly
 * these eight extensions on the prototype's "3D model" field, and every one of them is drawn by
 * `lib/modelViewerCore.ts` with a loader that ships in three.js itself. A format added there must be
 * added here, or the file is accepted and then shown as a plain download.
 *
 * DECIDED BY THE FILENAME FIRST, THE MIME TYPE SECOND. Browsers and handsets have no registered type
 * for most of these (a .glb uploaded from Windows arrives as `application/octet-stream`), so the
 * extension is the signal that is actually present; the `model/*` types are honoured when they are.
 */

export const MODEL_FORMATS = ["glb", "gltf", "stl", "obj", "ply", "3mf", "fbx", "usdz"] as const;

export type ModelFormat = (typeof MODEL_FORMATS)[number];

/** The registered or customary MIME types for the same formats. */
const MODEL_MIME_TYPES: Readonly<Record<string, ModelFormat>> = {
  "model/gltf-binary": "glb",
  "model/gltf+json": "gltf",
  "model/stl": "stl",
  "model/x.stl-binary": "stl",
  "model/x.stl-ascii": "stl",
  "application/sla": "stl",
  "model/obj": "obj",
  "model/3mf": "3mf",
  "application/vnd.ms-package.3dmanufacturing-3dmodel+xml": "3mf",
  "model/vnd.usdz+zip": "usdz",
  "model/x-ply": "ply"
};

/** The format of a stored file, or null when it is not a 3D model this app can draw. */
export function modelFormatOf(name: string | null | undefined, mimeType?: string | null): ModelFormat | null {
  const lower = (name ?? "").trim().toLowerCase();
  const dot = lower.lastIndexOf(".");
  if (dot >= 0) {
    const ext = lower.slice(dot + 1);
    if ((MODEL_FORMATS as readonly string[]).includes(ext)) return ext as ModelFormat;
  }
  const mime = (mimeType ?? "").trim().toLowerCase();
  return MODEL_MIME_TYPES[mime] ?? null;
}

/** The format's name as a person reads it — "GLB", "STL", "3MF". */
export function modelFormatLabel(format: ModelFormat): string {
  return format === "gltf" ? "glTF" : format.toUpperCase();
}


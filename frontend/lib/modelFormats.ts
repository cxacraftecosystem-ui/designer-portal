export const MODEL_FORMATS = ["glb", "gltf", "stl", "obj", "ply", "3mf", "fbx", "usdz"] as const;

export type ModelFormat = (typeof MODEL_FORMATS)[number];

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

export function modelFormatLabel(format: ModelFormat): string {
  return format === "gltf" ? "glTF" : format.toUpperCase();
}


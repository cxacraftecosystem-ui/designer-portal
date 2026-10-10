/**
 * The hash the handset's viewer bundle is stamped with: SHA-256 over the three source files it is
 * built from, with line endings normalised so a Windows checkout and a Linux runner agree.
 * Shared by `build-android-model-viewer.mjs` and `e2e/model-viewer-unit.spec.ts`.
 */

import { createHash } from "node:crypto";
import { readFileSync } from "node:fs";
import { join } from "node:path";

export const MODEL_VIEWER_SOURCES = ["lib/modelFormats.ts", "lib/modelViewerCore.ts", "lib/modelViewerAndroidEntry.ts"];

export function modelViewerSourceHash(frontendDir) {
  const hash = createHash("sha256");
  for (const file of MODEL_VIEWER_SOURCES) {
    hash.update(file);
    hash.update("\0");
    hash.update(readFileSync(join(frontendDir, file), "utf8").replace(/\r\n/g, "\n"));
    hash.update("\0");
  }
  return hash.digest("hex").slice(0, 16);
}

/**
 * The handset's entry into `modelViewerCore.ts`. NOT IMPORTED BY THE WEB — it is the input to
 * `scripts/build-android-model-viewer.mjs`, which bundles it with three.js into
 * `android/app/src/main/assets/model-viewer/viewer.js`.
 *
 * The page (`assets/model-viewer/index.html`) is opened by `DwModelViewer.kt` with the model's local
 * address and format in the query string. The model is read from the app's own storage through the
 * WebView's asset loader, so nothing here reaches the network, and the result is reported back to
 * the app through the `DwModelViewerHost` bridge so the screen around the WebView can say it.
 */

import { MODEL_FORMATS, type ModelFormat } from "./modelFormats";
import { ModelViewerError, mountModelViewer, type ModelViewerHandle } from "./modelViewerCore";

type Host = { onStatus(state: string, message: string): void };

function report(state: "ready" | "failed", message = ""): void {
  const host = (globalThis as unknown as { DwModelViewerHost?: Host }).DwModelViewerHost;
  host?.onStatus(state, message);
  const note = document.getElementById("note");
  if (note) {
    note.textContent = message;
    note.hidden = !message;
  }
}

let handle: ModelViewerHandle | null = null;

async function start(): Promise<void> {
  const params = new URLSearchParams(location.search);
  const src = params.get("src") ?? "";
  const format = (params.get("format") ?? "").toLowerCase();
  const container = document.getElementById("viewer");
  if (!container || !src || !(MODEL_FORMATS as readonly string[]).includes(format)) {
    report("failed", "This kind of file cannot be drawn as a 3D model.");
    return;
  }
  try {
    const response = await fetch(src);
    if (!response.ok) throw new ModelViewerError("The model's file could not be read on this phone. Try opening it again.");
    handle = await mountModelViewer(container, await response.arrayBuffer(), format as ModelFormat);
    report("ready");
  } catch (error) {
    report(
      "failed",
      error instanceof ModelViewerError ? error.message : "The model's file could not be read on this phone. Try opening it again."
    );
  }
}

// Called by the app's "Reset view" button.
(globalThis as unknown as { dwResetModelView?: () => void }).dwResetModelView = () => handle?.reset();
addEventListener("pagehide", () => handle?.dispose());

void start();

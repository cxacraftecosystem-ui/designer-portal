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

(globalThis as unknown as { dwResetModelView?: () => void }).dwResetModelView = () => handle?.reset();
addEventListener("pagehide", () => handle?.dispose());

void start();

"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { Box, Loader2, RotateCcw } from "lucide-react";

import { modelFormatLabel, type ModelFormat } from "@/lib/modelFormats";
import type { ModelViewerHandle } from "@/lib/modelViewerCore";

type Phase = { state: "idle" } | { state: "loading" } | { state: "ready" } | { state: "failed"; message: string };

export function ModelViewer({
  url,
  format,
  fileName,
  sizeLabel,
  className = "h-96"
}: {
  url: string;
  format: ModelFormat;
  fileName: string;
  sizeLabel?: string;
  className?: string;
}) {
  const [phase, setPhase] = useState<Phase>({ state: "idle" });
  const hostRef = useRef<HTMLDivElement | null>(null);
  const handleRef = useRef<ModelViewerHandle | null>(null);

  useEffect(() => {
    setPhase({ state: "idle" });
    return () => {
      handleRef.current?.dispose();
      handleRef.current = null;
    };
  }, [url, format]);

  const show = useCallback(async () => {
    setPhase({ state: "loading" });
    try {
      const [core, response] = await Promise.all([import("@/lib/modelViewerCore"), fetch(url)]);
      if (!response.ok) {
        setPhase({
          state: "failed",
          message: "The model could not be downloaded to show here. Check your connection and try again."
        });
        return;
      }
      const buffer = await response.arrayBuffer();
      const host = hostRef.current;
      if (!host) return;
      handleRef.current?.dispose();
      handleRef.current = await core.mountModelViewer(host, buffer, format);
      setPhase({ state: "ready" });
    } catch (error) {
      setPhase({
        state: "failed",
        message:
          error instanceof Error && error.name === "ModelViewerError"
            ? error.message
            : "The model could not be downloaded to show here. Check your connection and try again."
      });
    }
  }, [url, format]);

  return (
    <div className="grid gap-2" data-testid="model-viewer">
      <div
        className={`relative overflow-hidden rounded-md border border-line-200 bg-surface-50 ${className}`}
      >
        <div ref={hostRef} className="absolute inset-0" role="img" aria-label={`3D model: ${fileName}`} />
        {phase.state !== "ready" ? (
          <div className="absolute inset-0 grid place-items-center p-4 text-center text-xs leading-5 text-ink-500">
            {phase.state === "idle" ? (
              <button type="button" className="field-button" onClick={() => void show()}>
                <Box className="h-4 w-4" aria-hidden />
                Show in 3D · {modelFormatLabel(format)}
                {sizeLabel ? ` · ${sizeLabel}` : ""}
              </button>
            ) : phase.state === "loading" ? (
              <span className="inline-flex items-center gap-2" role="status">
                <Loader2 className="h-4 w-4 animate-spin" aria-hidden />
                Loading the model…
              </span>
            ) : (
              <span className="grid max-w-md gap-2" role="alert">
                {phase.message}
                <button type="button" className="field-button-secondary justify-self-center" onClick={() => void show()}>
                  Try again
                </button>
              </span>
            )}
          </div>
        ) : null}
      </div>
      {phase.state === "ready" ? (
        <div className="flex flex-wrap items-center gap-3 text-xs leading-5 text-ink-500">
          <span className="min-w-0 flex-1">Drag to turn it, scroll or pinch to zoom, and drag with two fingers or the right button to move it.</span>
          <button type="button" className="field-button-secondary" onClick={() => handleRef.current?.reset()}>
            <RotateCcw className="h-4 w-4" aria-hidden />
            Reset view
          </button>
        </div>
      ) : null}
    </div>
  );
}

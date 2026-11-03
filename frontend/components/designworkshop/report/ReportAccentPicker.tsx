"use client";

import {
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
  type KeyboardEvent as ReactKeyboardEvent,
  type PointerEvent as ReactPointerEvent
} from "react";
import { Palette } from "lucide-react";

import { AnchoredPopover } from "@/components/ui/AnchoredPopover";
import {
  ACCENT_PRESETS,
  DEFAULT_ACCENT,
  normaliseHex,
  paletteFromAccent,
  presetForAccent,
  type ReportPalette
} from "@/lib/reportTheme";

const PREVIEW_SETTLE_MS = 180;

type Hsv = { h: number; s: number; v: number };

const clamp01 = (value: number) => Math.max(0, Math.min(1, value));

function hsvToHex({ h, s, v }: Hsv): string {
  const sector = (((h % 360) + 360) % 360) / 60;
  const chroma = v * clamp01(s);
  const second = chroma * (1 - Math.abs((sector % 2) - 1));
  const base = v - chroma;
  let triple: [number, number, number];
  if (sector < 1) triple = [chroma, second, 0];
  else if (sector < 2) triple = [second, chroma, 0];
  else if (sector < 3) triple = [0, chroma, second];
  else if (sector < 4) triple = [0, second, chroma];
  else if (sector < 5) triple = [second, 0, chroma];
  else triple = [chroma, 0, second];
  return triple
    .map((channel) =>
      Math.floor(clamp01(channel + base) * 255 + 0.5)
        .toString(16)
        .toUpperCase()
        .padStart(2, "0")
    )
    .join("");
}

function hexToHsv(hex: string, fallbackHue = 0): Hsv {
  const red = parseInt(hex.slice(0, 2), 16) / 255;
  const green = parseInt(hex.slice(2, 4), 16) / 255;
  const blue = parseInt(hex.slice(4, 6), 16) / 255;
  const high = Math.max(red, green, blue);
  const span = high - Math.min(red, green, blue);
  let hue = fallbackHue;
  if (span > 0) {
    if (high === red) hue = ((((green - blue) / span) % 6) + 6) % 6;
    else if (high === green) hue = (blue - red) / span + 2;
    else hue = (red - green) / span + 4;
    hue *= 60;
  }
  return { h: hue, s: high === 0 ? 0 : span / high, v: high };
}

function fractionWithin(element: HTMLElement, clientX: number, clientY: number) {
  const rect = element.getBoundingClientRect();
  return {
    x: rect.width > 0 ? clamp01((clientX - rect.left) / rect.width) : 0,
    y: rect.height > 0 ? clamp01((clientY - rect.top) / rect.height) : 0
  };
}

function dragHandlers(onMove: (surface: HTMLElement, clientX: number, clientY: number) => void) {
  return {
    onPointerDown(event: ReactPointerEvent<HTMLDivElement>) {
      if (event.pointerType === "mouse" && event.button !== 0) return;
      const surface = event.currentTarget;
      surface.setPointerCapture(event.pointerId);
      event.preventDefault();
      surface.focus({ preventScroll: true });
      onMove(surface, event.clientX, event.clientY);
    },
    onPointerMove(event: ReactPointerEvent<HTMLDivElement>) {
      if (!event.currentTarget.hasPointerCapture(event.pointerId)) return;
      onMove(event.currentTarget, event.clientX, event.clientY);
    },
    onPointerUp(event: ReactPointerEvent<HTMLDivElement>) {
      if (event.currentTarget.hasPointerCapture(event.pointerId)) {
        event.currentTarget.releasePointerCapture(event.pointerId);
      }
    }
  };
}

type PickerSession = {
  base: Hsv;
  draft: Hsv | null;
  text: string;
};

export type ReportAccentPickerProps = {
  value: string;
  onChange: (accent: string) => void;
  palette: ReportPalette;
  savedAccent: string;
  onSave?: (accent: string) => void;
  saving?: boolean;
};

export function ReportAccentPicker({
  value,
  onChange,
  palette,
  savedAccent,
  onSave,
  saving
}: ReportAccentPickerProps) {
  const [session, setSession] = useState<PickerSession | null>(null);
  const anchorRef = useRef<HTMLDivElement | null>(null);
  const triggerRef = useRef<HTMLButtonElement | null>(null);
  const surfaceRef = useRef<HTMLDivElement | null>(null);

  const chosen = normaliseHex(value);
  const preset = presetForAccent(value);
  const saved = normaliseHex(savedAccent);
  const wellValue = chosen ? `#${chosen}` : palette.accent;
  const darkened = chosen ? palette.accent.toUpperCase() !== `#${chosen}` : false;

  const panelOpen = session !== null;
  const live = session ? (session.draft ?? session.base) : null;
  const liveHex = live ? hsvToHex(live) : null;
  const draftHex = session?.draft ? hsvToHex(session.draft) : null;

  const latest = useRef({ session, onChange, onSave });
  useEffect(() => {
    latest.current = { session, onChange, onSave };
  });

  const commit = useCallback(() => {
    const current = latest.current.session;
    setSession(null);
    if (!current?.draft) return;
    const hex = `#${hsvToHex(current.draft)}`;
    latest.current.onChange(hex);
    latest.current.onSave?.(hex);
  }, []);

  useEffect(() => {
    if (draftHex === null) return;
    const timer = setTimeout(() => latest.current.onChange(`#${draftHex}`), PREVIEW_SETTLE_MS);
    return () => clearTimeout(timer);
  }, [draftHex]);

  useEffect(() => {
    if (!panelOpen) return;
    const frame = requestAnimationFrame(() => surfaceRef.current?.focus({ preventScroll: true }));
    return () => cancelAnimationFrame(frame);
  }, [panelOpen]);

  const openPanel = useCallback(() => {
    const start = normaliseHex(wellValue) ?? normaliseHex(DEFAULT_ACCENT)!;
    setSession({ base: hexToHsv(start), draft: null, text: start });
  }, [wellValue]);

  const choose = useCallback((next: (from: Hsv) => Hsv) => {
    setSession((current) => {
      if (!current) return current;
      const moved = next(current.draft ?? current.base);
      return { ...current, draft: moved, text: hsvToHex(moved) };
    });
  }, []);

  const surfaceDrag = useMemo(
    () =>
      dragHandlers((surface, clientX, clientY) => {
        const { x, y } = fractionWithin(surface, clientX, clientY);
        choose((from) => ({ h: from.h, s: x, v: 1 - y }));
      }),
    [choose]
  );

  const hueDrag = useMemo(
    () =>
      dragHandlers((surface, clientX) => {
        const { x } = fractionWithin(surface, clientX, 0);
        choose((from) => ({ ...from, h: x * 360 }));
      }),
    [choose]
  );

  function onSurfaceKeyDown(event: ReactKeyboardEvent<HTMLDivElement>) {
    const step = event.shiftKey ? 0.1 : 0.01;
    if (event.key === "ArrowLeft") choose((from) => ({ ...from, s: clamp01(from.s - step) }));
    else if (event.key === "ArrowRight") choose((from) => ({ ...from, s: clamp01(from.s + step) }));
    else if (event.key === "ArrowUp") choose((from) => ({ ...from, v: clamp01(from.v + step) }));
    else if (event.key === "ArrowDown") choose((from) => ({ ...from, v: clamp01(from.v - step) }));
    else return;
    event.preventDefault();
  }

  function onHueKeyDown(event: ReactKeyboardEvent<HTMLDivElement>) {
    const step = event.shiftKey ? 15 : 1;
    const turn = (from: Hsv, by: number) => ({ ...from, h: (((from.h + by) % 360) + 360) % 360 });
    if (event.key === "ArrowLeft" || event.key === "ArrowDown") choose((from) => turn(from, -step));
    else if (event.key === "ArrowRight" || event.key === "ArrowUp") choose((from) => turn(from, step));
    else return;
    event.preventDefault();
  }

  return (
    <div className="grid gap-3">
      <div className="flex items-start gap-3">
        <span className="mt-0.5 grid h-9 w-9 shrink-0 place-items-center rounded-xl bg-field-200 text-field-600 dark:text-purple-300">
          <Palette className="h-4 w-4" aria-hidden />
        </span>
        <div className="min-w-0">
          <h3 className="font-display text-base font-bold text-ink-900">Report colour</h3>
          <p className="mt-1 text-sm leading-6 text-ink-muted">
            One accent colour for headings, tables and figures.
          </p>
        </div>
      </div>

      <div role="radiogroup" aria-label="Report colour" className="flex flex-wrap gap-2">
        {ACCENT_PRESETS.map((option) => {
          const selected = preset?.key === option.key;
          return (
            <button
              key={option.key}
              type="button"
              role="radio"
              aria-checked={selected}
              onClick={() => onChange(option.hex)}
              className={`flex items-center gap-2 rounded-lg border px-2.5 py-1.5 text-sm transition ${
                selected
                  ? "border-purple-700 bg-purple-50 font-medium text-ink-900"
                  : "border-line-200 text-ink-700 hover:border-line-300 hover:bg-surface-50"
              }`}
            >
              <span
                className="h-4 w-4 shrink-0 rounded ring-1 ring-inset ring-black/20"
                style={{ background: option.hex }}
                aria-hidden
              />
              {option.label}
            </button>
          );
        })}
      </div>

      <div className="flex flex-wrap items-center gap-x-4 gap-y-2">
        <div ref={anchorRef} className="inline-flex">
          <button
            ref={triggerRef}
            type="button"
            aria-haspopup="dialog"
            aria-expanded={panelOpen}
            onClick={() => (panelOpen ? commit() : openPanel())}
            className="flex items-center gap-2 rounded-lg border border-line-200 px-2.5 py-1.5 text-sm text-ink-700 transition hover:border-purple-300 hover:bg-purple-50"
          >
            <span
              className="h-4 w-4 shrink-0 rounded ring-1 ring-inset ring-black/20"
              style={{ background: wellValue }}
              aria-hidden
            />
            Custom colour
          </button>
        </div>

        {chosen && onSave && chosen !== saved ? (
          <button
            type="button"
            className="field-button-secondary"
            disabled={saving}
            onClick={() => onSave(`#${chosen}`)}
          >
            {saving ? "Saving…" : "Save as the workshop's colour"}
          </button>
        ) : null}

        {chosen ? (
          <button type="button" className="field-button-secondary" onClick={() => onChange("")}>
            Use the saved colour
          </button>
        ) : null}

        <span className="text-sm text-ink-muted">
          {saving
            ? "Keeping this colour on the workshop…"
            : !chosen
              ? saved
                ? "Saved colour from stage 20."
                : "Template colour."
              : preset
                ? `${preset.label} — this file only.`
                : "Custom — this file only."}
        </span>
      </div>

      <AnchoredPopover
        open={panelOpen}
        onClose={commit}
        anchorRef={anchorRef}
        label="Custom report colour"
        restoreFocusRef={triggerRef}
        maxWidth={288}
        className="w-72"
      >
        {session && live && liveHex ? (
          <div className="grid gap-3">
            <div
              ref={surfaceRef}
              role="slider"
              tabIndex={0}
              aria-label="Colour saturation and brightness"
              aria-valuemin={0}
              aria-valuemax={100}
              aria-valuenow={Math.round(live.s * 100)}
              aria-valuetext={`Saturation ${Math.round(live.s * 100)} percent, brightness ${Math.round(live.v * 100)} percent`}
              onKeyDown={onSurfaceKeyDown}
              {...surfaceDrag}
              style={{
                touchAction: "none",
                backgroundColor: `hsl(${live.h} 100% 50%)`,
                backgroundImage:
                  "linear-gradient(to top, rgb(0 0 0) 0%, rgb(0 0 0 / 0) 100%), " +
                  "linear-gradient(to right, rgb(255 255 255) 0%, rgb(255 255 255 / 0) 100%)"
              }}
              className="relative h-40 w-full cursor-crosshair rounded-md ring-1 ring-inset ring-black/20"
            >
              <span
                aria-hidden
                className="pointer-events-none absolute h-4 w-4 -translate-x-1/2 -translate-y-1/2 rounded-full border-2 border-white shadow-sm ring-1 ring-black/40"
                style={{ left: `${live.s * 100}%`, top: `${(1 - live.v) * 100}%`, backgroundColor: `#${liveHex}` }}
              />
            </div>

            <div
              role="slider"
              tabIndex={0}
              aria-label="Colour hue"
              aria-valuemin={0}
              aria-valuemax={360}
              aria-valuenow={Math.round(live.h)}
              aria-valuetext={`${Math.round(live.h)} degrees`}
              onKeyDown={onHueKeyDown}
              {...hueDrag}
              style={{
                touchAction: "none",
                backgroundImage:
                  "linear-gradient(to right, #FF0000 0%, #FFFF00 17%, #00FF00 33%, #00FFFF 50%, #0000FF 67%, #FF00FF 83%, #FF0000 100%)"
              }}
              className="relative h-4 w-full cursor-ew-resize rounded-full ring-1 ring-inset ring-black/20"
            >
              <span
                aria-hidden
                className="pointer-events-none absolute top-1/2 h-5 w-5 -translate-x-1/2 -translate-y-1/2 rounded-full border-2 border-white shadow-sm ring-1 ring-black/40"
                style={{ left: `${(live.h / 360) * 100}%`, backgroundColor: `hsl(${live.h} 100% 50%)` }}
              />
            </div>

            <div className="flex items-center gap-2">
              <span aria-hidden className="font-mono text-sm text-ink-muted">
                #
              </span>
              <input
                className="field-input w-28 font-mono uppercase"
                value={session.text}
                spellCheck={false}
                maxLength={7}
                aria-label="Colour hex code"
                onChange={(event) => {
                  const text = event.target.value;
                  setSession((current) => {
                    if (!current) return current;
                    const hex = normaliseHex(text);
                    const from = current.draft ?? current.base;
                    return { ...current, text, draft: hex ? hexToHsv(hex, from.h) : current.draft };
                  });
                }}
              />
              <span
                aria-hidden
                className="ml-auto h-9 w-9 shrink-0 rounded-md ring-1 ring-inset ring-black/20"
                style={{ backgroundColor: `#${liveHex}` }}
              />
            </div>

            <button
              type="button"
              className="field-button"
              onClick={() => {
                commit();
                triggerRef.current?.focus({ preventScroll: true });
              }}
            >
              Use this colour
            </button>
            <p className="text-xs leading-5 text-ink-muted">
              Closing keeps the colour and saves it to the workshop.
            </p>
          </div>
        ) : null}
      </AnchoredPopover>

      {darkened ? (
        <p className="rounded-md border border-line-200 bg-surface-50 px-3 py-2 text-sm leading-6 text-ink-700">
          Too pale to read, so the report uses{" "}
          <code className="font-mono text-xs">{palette.accent.toUpperCase()}</code>, slightly darker.
        </p>
      ) : null}
    </div>
  );
}

export function resolvePreviewPalette(
  override: string,
  savedAccent: string
): { palette: ReportPalette; accent: string; chosen: boolean } {
  const accent = normaliseHex(override) ?? normaliseHex(savedAccent);
  return {
    palette: paletteFromAccent(accent),
    accent: accent ? `#${accent}` : "",
    chosen: Boolean(accent)
  };
}

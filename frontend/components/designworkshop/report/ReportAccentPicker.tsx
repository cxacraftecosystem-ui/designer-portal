"use client";

/**
 * The report's accent colour: twelve named swatches, a draggable colour panel, and the document
 * redrawn.
 *
 * WHY THE CHOICE IS SAFE TO OFFER AT ALL. A colour picker on a document that goes to a ministry is
 * a way to produce an unreadable submission, and this one cannot, for two reasons that live
 * elsewhere and are only surfaced here. The designer picks ONE colour and `lib/reportTheme` derives
 * the other seven, so no combination of independently-chosen colours exists to get wrong; and the
 * sheets below are redrawn in the result immediately, so the choice is made while looking at the
 * document rather than at a paint chip. Neither is decoration: the failure this replaces is
 * generating a .docx, opening it in Word, disliking the colour, and going round again.
 *
 * TWELVE NAMES, THEN A COLOUR PANEL — in that order and in that proportion. The twelve are the
 * answer for almost everybody: they suit a government report, they are spaced on a lightness
 * ladder so they stay apart when the file is printed on the office laser, and each has a NAME. The
 * name is not a label, it is the feature: a designer who chose "Maroon" can say that to the officer
 * who asked for it, and can choose it again next year on a different laptop. `#802F42` is not
 * something anybody says out loud. The panel is there because somebody will eventually be handed a
 * brand colour by an implementing agency, and refusing them would send them to editing the .docx by
 * hand.
 *
 * ⚠ THE SWATCH SHOWS THE DERIVED COLOUR, NOT THE REQUESTED ONE. A colour too pale to read as a
 * heading is darkened by the derivation, and a picker that showed the pale version while the paper
 * got the dark one would be lying at the exact moment it is being trusted. Where the two differ the
 * panel says so in words.
 *
 * ── The colour panel, and the three things it was rebuilt to fix ─────────────────────────────
 *
 * IT DRAGS. This used to be a native `<input type="color">`, which on a laptop means a second,
 * modal, OS-drawn window that covers the pages the whole feature exists to let you look at — and on
 * a tablet, which is what designers actually carry into a workshop, it means whatever the platform
 * feels like. The saturation square and the hue strip below are ordinary elements, so the document
 * stays visible behind them and a finger works exactly like a cursor. The gesture is Pointer Events
 * with `setPointerCapture` (never a document-level `mousemove`): the capture routes every move and
 * the release back to the surface even when the finger runs off the edge of the square, and the
 * browser drops it on pointerup, pointercancel and unmount alike, so there is no listener anybody
 * can forget to remove. `touch-action: none` on both surfaces is not optional — without it the
 * browser claims the drag as a page scroll and the colour never moves at all.
 *
 * IT ASKS. "Use this colour" is an explicit confirmation, because a value that becomes final with
 * no acknowledgement leaves a designer unsure whether the thing they just did counted.
 *
 * BUT DISMISSING IT STILL APPLIES. Escape, a click back onto the page, tabbing away, walking off
 * the screen — every one of those commits the colour, through {@link ReportAccentPicker.commit},
 * which is the *same function* the confirm button calls. The confirmation is a reassurance, not a
 * gate: somebody who has dragged a colour and then clicked back onto the document has chosen it,
 * and throwing that away because the wrong gesture ended the gesture is the defect this shape
 * exists to prevent. The only controls that undo a colour are the labelled ones — "Use the saved
 * colour" below, and the twelve swatches.
 *
 * NOTHING THE DRAG DOES COSTS A ROUND TRIP OR A REDRAW. Pointer movement writes local state only,
 * so it re-renders this panel and not the forty sheets underneath it; the page is told the new
 * colour once the pointer has been still for {@link PREVIEW_SETTLE_MS}. That debounce is the fix
 * for a reported bug — the old control fired `change` dozens of times a second while the pointer
 * moved, each one re-deriving the palette and re-rendering the cover, the maps, the SVG charts and
 * forty-odd pages of blocks, and the colour lagged the cursor by a second or more.
 *
 * ── What is per-file and what is kept ────────────────────────────────────────────────────────
 *
 * The twelve swatches are a per-FILE override: the value is sent on the download and stage 20 is
 * untouched, so trying three colours before submitting does not mean three saves. The colour panel
 * is the other case — a designer reaches for it because they have been given a brand colour and
 * intend to keep it — so committing one both applies it to this file AND writes it to stage 20
 * through `onSave`. Both, and in that order: the override is what redraws the sheets in the same
 * frame, and it is also what keeps the colour on this file if the write fails, so a designer on a
 * dropped connection still gets the report they asked for.
 */

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

/**
 * How long the pointer has to be still before the page — and with it every sheet below — is redrawn
 * in the new colour.
 *
 * Long enough that a drag across the whole gamut costs one redraw, short enough that releasing the
 * pointer feels immediate.
 */
const PREVIEW_SETTLE_MS = 180;

/* ────────────────────────────────────────────────────────────────────────────
 * HSV — for the drag surfaces, and for nothing else
 *
 * NOT added to `lib/reportTheme`: that module is a line-for-line port of the server's
 * `report_theme.py` checked against a golden table, and handle positions are none of its business.
 * What leaves this panel is a plain `#RRGGBB`, and the derivation that decides what the paper
 * actually gets happens exactly where it always did.
 *
 * HSV rather than that module's HSL because the two-dimensional area a designer drags is a
 * saturation × brightness square. In HSL the same square is a cone that pinches shut at both ends —
 * the whole top row is white at every saturation — so the handle stops corresponding to anything
 * and a drag along the top edge changes no colour at all.
 * ──────────────────────────────────────────────────────────────────────────── */

type Hsv = { h: number; s: number; v: number };

const clamp01 = (value: number) => Math.max(0, Math.min(1, value));

/**
 * `hsv` as a bare upper-case `RRGGBB`.
 *
 * `Math.floor(x + 0.5)` rather than `Math.round`, for the reason `reportTheme.toHex` gives at
 * length: the colour chosen here is compared, character for character, against the server's own
 * rounding of the same value, and the two languages disagree about halves.
 */
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

/**
 * A bare `RRGGBB` as HSV, keeping `fallbackHue` for a colour that has no hue of its own.
 *
 * Black, white and every grey sit on the achromatic axis where hue is undefined — computing one
 * returns 0, which is red. Without the fallback, dragging the brightness handle down to the bottom
 * edge and back up would silently turn the designer's teal into red on the way, and the hue strip
 * would jump to the far left underneath their other hand.
 */
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

/** Where a pointer sits inside `element`, as 0..1 on each axis, clamped to the box. */
function fractionWithin(element: HTMLElement, clientX: number, clientY: number) {
  const rect = element.getBoundingClientRect();
  return {
    x: rect.width > 0 ? clamp01((clientX - rect.left) / rect.width) : 0,
    y: rect.height > 0 ? clamp01((clientY - rect.top) / rect.height) : 0
  };
}

/**
 * The handlers that make an element respond to a press-and-drag, with the pointer captured.
 *
 * `setPointerCapture` and not a listener on `document`: the capture re-targets every subsequent
 * move and the release to this element, so a drag that runs off the bottom of the square keeps
 * tracking, and the browser releases it on pointerup, pointercancel and removal alike — there is no
 * subscription for a re-render or an unmount to strand. The hand-rolled version of this is the one
 * that leaves the colour following the cursor after the button is already up.
 *
 * `onMove` is called on the press as well as on every move, because a plain click on the square is
 * a drag of length zero and must land on the colour under the pointer.
 */
function dragHandlers(onMove: (surface: HTMLElement, clientX: number, clientY: number) => void) {
  return {
    onPointerDown(event: ReactPointerEvent<HTMLDivElement>) {
      // A right-click is a context menu, not a colour.
      if (event.pointerType === "mouse" && event.button !== 0) return;
      const surface = event.currentTarget;
      surface.setPointerCapture(event.pointerId);
      // Suppresses the text selection and the native image drag that a press otherwise starts,
      // either of which ends the gesture the moment the pointer leaves the square. It also
      // suppresses the focus the press would have moved, hence the explicit call below.
      event.preventDefault();
      surface.focus({ preventScroll: true });
      onMove(surface, event.clientX, event.clientY);
    },
    onPointerMove(event: ReactPointerEvent<HTMLDivElement>) {
      // Only while the button is down. Without this test the surface would track a bare hover.
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

/**
 * One opening of the colour panel.
 *
 * `base` is the colour the panel opened on and never moves; `draft` is null until the designer has
 * actually chosen something. The distinction is what stops merely opening and closing the panel —
 * a stray click, a change of mind — from writing the workshop's saved colour back over itself.
 */
type PickerSession = {
  base: Hsv;
  draft: Hsv | null;
  /** What is in the hex box, which may be half-typed and therefore not yet a colour. */
  text: string;
};

export type ReportAccentPickerProps = {
  /** The chosen accent as `#RRGGBB`, or "" for "whatever stage 20 and the template say". */
  value: string;
  onChange: (accent: string) => void;
  /** The palette `value` derives to — computed once by the page and shared with the preview. */
  palette: ReportPalette;
  /** What stage 20 has saved, so the panel can say whether this is an override or a match. */
  savedAccent: string;
  /**
   * Persist this colour to stage 20.
   *
   * Called by the "Save as the workshop's colour" button below AND by every commit out of the
   * colour panel, confirmed or dismissed. Omitted, neither offers to keep anything and the picker
   * is a per-file override throughout.
   */
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
  // The colour well cannot hold "no choice": it always shows something, and showing black while
  // nothing is chosen would read as a choice of black. It shows what the sheets are drawn in.
  const wellValue = chosen ? `#${chosen}` : palette.accent;
  // Set when the derivation had to darken what was asked for. Said in words rather than left for
  // the designer to notice by comparing a swatch with a heading.
  const darkened = chosen ? palette.accent.toUpperCase() !== `#${chosen}` : false;

  const panelOpen = session !== null;
  const live = session ? (session.draft ?? session.base) : null;
  const liveHex = live ? hsvToHex(live) : null;
  const draftHex = session?.draft ? hsvToHex(session.draft) : null;

  /**
   * The live values `commit` needs, read through a ref so `commit` itself never changes identity.
   *
   * `AnchoredPopover` puts `onClose` in four dependency arrays — Escape, the outside click, the
   * focus watch and the scroll follower — so a `commit` that changed on every pointer move would
   * tear those four listeners down and re-register them, and re-run the placement measurement, once
   * per pointer event. That is precisely the per-event cost this control was rebuilt to remove.
   */
  const latest = useRef({ session, onChange, onSave });
  useEffect(() => {
    latest.current = { session, onChange, onSave };
  });

  /**
   * Apply the colour the panel is showing, and close it.
   *
   * THE ONLY EXIT, AND THAT IS THE POINT. The confirm button calls this; so does every dismissal,
   * because `AnchoredPopover`'s `onClose` IS this function — Escape, a click back onto the page,
   * tabbing out and the anchor scrolling away all arrive here. A dismissed pick therefore reaches
   * stage 20 through the identical two calls a confirmed one does, and there is no quieter second
   * path for one of them to be missing from.
   */
  const commit = useCallback(() => {
    const current = latest.current.session;
    setSession(null);
    // Opened and closed without moving anything is not a choice. Treating it as one would write
    // the workshop's own saved colour back over itself on every stray click of the trigger.
    if (!current?.draft) return;
    const hex = `#${hsvToHex(current.draft)}`;
    latest.current.onChange(hex);
    latest.current.onSave?.(hex);
  }, []);

  /**
   * Tell the page once the pointer has settled — never on the pointer event itself.
   *
   * This is the whole anti-lag contract: dragging writes `session` and re-renders this panel, which
   * is a handful of nodes, while the sheets below re-render at most once per pause. `onChange` is
   * read off the ref so a caller passing an inline arrow cannot restart the timer on every render
   * and starve the redraw entirely.
   */
  useEffect(() => {
    if (draftHex === null) return;
    const timer = setTimeout(() => latest.current.onChange(`#${draftHex}`), PREVIEW_SETTLE_MS);
    return () => clearTimeout(timer);
  }, [draftHex]);

  /**
   * Move focus into the panel when it opens.
   *
   * `AnchoredPopover` deliberately does not do this — it was written around a text input the user
   * must be able to keep typing into — but this trigger is a button, and the panel is portalled to
   * the end of `<body>`, so Tab from the trigger lands on the next field of the report page rather
   * than on the square. Without this the control would be pointer-only.
   *
   * Deferred one frame because the popover resolves its portal host in an effect of its own: on the
   * render where `panelOpen` first turns true the panel is not in the document yet and the ref is
   * still null.
   */
  useEffect(() => {
    if (!panelOpen) return;
    const frame = requestAnimationFrame(() => surfaceRef.current?.focus({ preventScroll: true }));
    return () => cancelAnimationFrame(frame);
  }, [panelOpen]);

  const openPanel = useCallback(() => {
    const start = normaliseHex(wellValue) ?? normaliseHex(DEFAULT_ACCENT)!;
    setSession({ base: hexToHsv(start), draft: null, text: start });
  }, [wellValue]);

  /** Replace the drafted colour, keeping the hex box in step with the handles. */
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
    // A whole percent per press, ten with Shift — the same ladder every range control in the app
    // offers, and enough to cross the square in a few seconds rather than a few hundred presses.
    const step = event.shiftKey ? 0.1 : 0.01;
    if (event.key === "ArrowLeft") choose((from) => ({ ...from, s: clamp01(from.s - step) }));
    else if (event.key === "ArrowRight") choose((from) => ({ ...from, s: clamp01(from.s + step) }));
    else if (event.key === "ArrowUp") choose((from) => ({ ...from, v: clamp01(from.v + step) }));
    else if (event.key === "ArrowDown") choose((from) => ({ ...from, v: clamp01(from.v - step) }));
    else return;
    // Only once a key has been recognised: Escape and Tab must still reach the popover, which is
    // what closes it — and closing it is what applies the colour.
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
        <span className="mt-0.5 grid h-9 w-9 shrink-0 place-items-center rounded-xl bg-field-200 text-field-600">
          <Palette className="h-4 w-4" aria-hidden />
        </span>
        <div className="min-w-0">
          <h3 className="font-display text-base font-bold text-ink-900">Report colour</h3>
          <p className="mt-1 text-sm leading-6 text-ink-muted">
            One accent colour. The headings, the table headers, the rules, the zebra stripes and the figures are all derived
            from it, so the whole document stays one palette and the table header&apos;s text is always readable on it. The
            pages below are redrawn as you choose.
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
              {/* A ring rather than a bare square: several of the twelve are dark enough that the
                  swatch would otherwise merge into the border on a dark theme. */}
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
        {/* The anchor is the wrapper and not the button, so the panel is measured against the whole
            control — and so a click on the trigger while the panel is open is not read as a click
            outside it. */}
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

        {/*
          SAVING IS ALSO A SEPARATE, EXPLICIT ACT — and it exists at all because without it the
          twelve swatches were a dead end. A colour picked from them is a per-file override: it is
          sent on the download and deliberately not written to stage 20, so a designer could try
          three colours without saving three times. What nobody could do was KEEP one. They picked a
          colour, generated the report, came back the next morning and found the old colour, with
          nothing on screen suggesting the choice had been temporary.

          The colour panel above does not need this button — committing out of it already writes
          stage 20 — which is why it disappears the moment the two agree.
        */}
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
                ? "Using the colour saved on stage 20 for this file."
                : "No colour chosen — the report keeps the template’s own."
              : preset
                ? `${preset.label} — this file only. Stage 20 is untouched.`
                : "A custom colour, for this file only. Stage 20 is untouched."}
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
              // A two-dimensional surface has no single number to report, and `slider` requires
              // one. `aria-valuenow` therefore carries the horizontal axis — the axis the left and
              // right arrows move, which is what a reader will assume it means — and `aria-valuetext`
              // carries the honest reading of both. Assistive technology announces the text where
              // there is one, so the vertical axis is never silent; the number is the fallback.
              aria-valuemin={0}
              aria-valuemax={100}
              aria-valuenow={Math.round(live.s * 100)}
              aria-valuetext={`Saturation ${Math.round(live.s * 100)} percent, brightness ${Math.round(live.v * 100)} percent`}
              onKeyDown={onSurfaceKeyDown}
              {...surfaceDrag}
              // touch-action inline rather than as a class, matching the nav's scrim: this one
              // declaration is the difference between a drag and the browser scrolling the page out
              // from under the designer's finger, and it must not depend on a purge pass.
              style={{
                touchAction: "none",
                backgroundColor: `hsl(${live.h} 100% 50%)`,
                // The black layer is listed first because the first layer paints on top. Explicit
                // fully-transparent RGB rather than the `transparent` keyword so no browser
                // interpolates through transparent black and bands the middle of the square grey.
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

            {/* The hex box is what an implementing agency's brand colour arrives as. It accepts a
                half-typed value without snapping the handles about, because `normaliseHex` returns
                null until there are six digits and the drafted colour is simply left where it was. */}
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

            {/*
              The confirmation, and the sentence that makes it honest.

              A designer who has just dragged a colour onto a document bound for a ministry wants to
              be told it counted — hence the button. But the button is NOT a gate: closing this panel
              any other way runs the same `commit`, so the note says so rather than leaving somebody
              to find out by losing a colour once.
            */}
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
              Closing this panel keeps the colour too — nothing you have chosen here is thrown away. It is applied to this
              file and saved as the workshop&apos;s colour.
            </p>
          </div>
        ) : null}
      </AnchoredPopover>

      {darkened ? (
        // The one place the derivation visibly disagrees with the request, so it is stated. A pale
        // accent is a heading nobody can read, and the alternative to darkening it was refusing
        // the colour outright — which is not what somebody standing in a district office needs.
        <p className="rounded-md border border-line-200 bg-surface-50 px-3 py-2 text-sm leading-6 text-ink-700">
          That colour is too pale to read as a heading on white paper, so the report uses{" "}
          <code className="font-mono text-xs">{palette.accent.toUpperCase()}</code> — the same colour, darkened just enough
          to be legible. The swatches and the pages below show what the file will actually contain.
        </p>
      ) : null}
    </div>
  );
}

/**
 * The palette a report page should draw itself in, given the override and the saved settings.
 *
 * THE SAME ORDER THE SERVER RESOLVES IT IN — `report_theme.resolve_accent`: this file's override
 * first, the saved stage-20 answer second, nothing third. A preview that resolved it differently
 * from the generator would be a preview of a document that does not exist, which is the one thing
 * this whole screen is built to prevent.
 */
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

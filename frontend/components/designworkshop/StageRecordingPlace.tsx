"use client";

/**
 * WHERE AND WHEN THIS STAGE IS BEING RECORDED — one card at the top of the stage, feeding every
 * photograph, recording and file the stage uploads.
 *
 * WHY IT IS NOT A REGISTRY FIELD, which is the first thing a reader will ask. `save_stage` drops any
 * key the registry does not declare and reports it in `droppedKeys`, so inventing a `_recordedAt`
 * would produce a banner on every save telling the designer their answer was not stored — which
 * would be the literal truth. The place of recording therefore lives where this repository already
 * keeps provenance: on the `MediaFile` rows. `uploadMediaBatch` takes `location`, `recordedAt` and
 * `recordedTimezone`, `attach_location` writes a `Location` row from them, and the whole of
 * `/media`, the data browser and the exports already know how to read it back. One card here fills
 * that in for all eleven media fields of a stage instead of asking eleven times and getting eleven
 * different answers.
 *
 * IT NEVER CAPTURES BY ITSELF, and that is the same rule the artisan form's location card enforces
 * for the same reason. A stage is very often filled in at a desk a week after the workshop, from
 * photographs taken in a courtyard 1,500 km away. A fix taken on mount would stamp the desk onto
 * every one of them and call it the place of recording — which is precisely the defect that put
 * fifteen Kharagpur coordinates on artisans in Bagru, Kutch and Rudraprayag. Nothing is captured
 * until somebody presses a button, and an empty card means the media is uploaded with no location,
 * exactly as it was before this card existed.
 *
 * THE TWO GROUPS ARE NOT ONE GROUP. The coordinates are where the DEVICE is — a measurement, with a
 * radius. The state, district and village are a STATEMENT by the designer about where the workshop
 * was held. They are stored in different columns, they can legitimately disagree, and the card keeps
 * them visibly apart so nobody reads a device fix as an address.
 */

import { useEffect, useId, useState } from "react";
import { ChevronDown, LocateFixed, MapPinned } from "lucide-react";

import {
  loadAddressReference,
  OFFLINE_STATES,
  pincodeValidationError,
  postalZoneMismatch
} from "@/components/forms/LocationFields";
import { MapPointPicker, mapPickerAvailable } from "@/components/forms/MapPointPicker";
import { Dropdown } from "@/components/ui/Dropdown";
import type { DwGeoValue } from "@/lib/designWorkshops";
import type { AddressReference } from "@/lib/types";

/** Everything this card hands to an upload, in `uploadMediaBatch`'s own vocabulary. */
export type StageRecordingPlace = {
  /** The device fix, for a GEO field that wants to copy it. Null until somebody captures one. */
  point: DwGeoValue | null;
  /** The `location` payload — undefined when there is no coordinate, because a Location row needs one. */
  location: Record<string, unknown> | undefined;
  recordedAt: string | undefined;
  recordedTimezone: string;
};

export const EMPTY_RECORDING_PLACE: StageRecordingPlace = {
  point: null,
  location: undefined,
  recordedAt: undefined,
  recordedTimezone: "Asia/Kolkata"
};

/**
 * The interpretation timezone.
 *
 * `Asia/Kolkata` is the fallback the rest of this repository uses (`recordedTimezoneFromForm`), and
 * the browser's own zone is preferred over it because a designer working from a university abroad
 * would otherwise have every timestamp re-read five and a half hours out. Falling back rather than
 * failing: `resolvedOptions()` is missing on old WebViews and throwing here would take the stage
 * down over a timestamp label.
 */
function browserTimezone(): string {
  try {
    return Intl.DateTimeFormat().resolvedOptions().timeZone || "Asia/Kolkata";
  } catch {
    return "Asia/Kolkata";
  }
}

const FIX_TIMEOUT_MS = 30_000;

export function StageRecordingPlaceCard({
  value,
  onChange,
  disabled
}: {
  value: StageRecordingPlace;
  onChange: (next: StageRecordingPlace) => void;
  disabled?: boolean;
}) {
  const headingId = useId();
  const [open, setOpen] = useState(false);
  const [busy, setBusy] = useState(false);
  const [problem, setProblem] = useState<string | null>(null);
  const [mapOpen, setMapOpen] = useState(false);
  const [reference, setReference] = useState<AddressReference | null>(null);

  const [stateName, setStateName] = useState("");
  const [district, setDistrict] = useState("");
  const [village, setVillage] = useState("");
  const [pincode, setPincode] = useState("");

  useEffect(() => {
    let live = true;
    loadAddressReference()
      .then((payload) => {
        if (live) setReference(payload);
      })
      .catch(() => {
        // Offline, which out here is a normal minute of the morning. The state dropdown falls back
        // to the bundled 36 and the district simply offers nothing — neither may cost a record,
        // because none of this is required.
      });
    return () => {
      live = false;
    };
  }, []);

  /**
   * Rebuild the payload from the four stated boxes and whatever coordinate is standing.
   *
   * Everything goes through here rather than each control patching `value` itself, because the
   * `location` payload is only valid AS A WHOLE: `locationFromForm` returns undefined without a
   * coordinate, and a state typed with no pin has nowhere to be stored. One place to get that right.
   */
  function publish(
    point: DwGeoValue | null,
    stated: { state: string; district: string; village: string; pincode: string },
    capturedAt: string | undefined
  ) {
    if (!point) {
      onChange({ ...EMPTY_RECORDING_PLACE, recordedTimezone: browserTimezone() });
      return;
    }
    onChange({
      point,
      location: {
        latitude: point.lat,
        longitude: point.lon,
        accuracy: point.accuracy,
        capturedAt,
        state: stated.state || undefined,
        district: stated.district || undefined,
        village: stated.village || undefined,
        pincode: stated.pincode || undefined
      },
      recordedAt: capturedAt,
      recordedTimezone: browserTimezone()
    });
  }

  const stated = { state: stateName, district, village, pincode };
  const capturedAtRef = value.recordedAt;

  function setPoint(point: DwGeoValue | null, capturedAt: string | undefined) {
    publish(point, stated, capturedAt);
  }

  function setStated(next: Partial<typeof stated>) {
    const merged = { ...stated, ...next };
    if (next.state !== undefined) setStateName(next.state);
    if (next.district !== undefined) setDistrict(next.district);
    if (next.village !== undefined) setVillage(next.village);
    if (next.pincode !== undefined) setPincode(next.pincode);
    publish(value.point, merged, capturedAtRef);
  }

  function captureFix() {
    if (typeof navigator === "undefined" || !navigator.geolocation) {
      setProblem("This browser cannot report a location. Point at the place on the map instead.");
      return;
    }
    setBusy(true);
    setProblem(null);
    navigator.geolocation.getCurrentPosition(
      (position) => {
        setBusy(false);
        setPoint(
          {
            lat: position.coords.latitude,
            lon: position.coords.longitude,
            // The radius travels with the fix or the fix cannot be judged later. A browser with no
            // satellite lock silently falls back to Wi-Fi or the IP address and reports kilometres
            // while returning coordinates that look every bit as precise.
            ...(Number.isFinite(position.coords.accuracy) ? { accuracy: position.coords.accuracy } : {})
          },
          new Date().toISOString()
        );
      },
      (failure) => {
        setBusy(false);
        setProblem(
          failure.code === failure.PERMISSION_DENIED
            ? "Location permission was refused for this site. Allow it in the address-bar permissions, or point at the place on the map."
            : failure.code === failure.POSITION_UNAVAILABLE
              ? "The device has no location to give — check that location services are switched on."
              : "The device could not get a fix in time. Try again outdoors, or point at the place on the map."
        );
      },
      { enableHighAccuracy: true, timeout: FIX_TIMEOUT_MS, maximumAge: 0 }
    );
  }

  const stateOptions = (reference?.statesAndUnionTerritories ?? OFFLINE_STATES).map((name) => ({
    value: name,
    label: name
  }));
  const districtOptions = ((stateName && reference?.districts?.byState?.[stateName]) || []).map((name) => ({
    value: name,
    label: name
  }));
  const pincodeProblem = pincodeValidationError(pincode);
  const zoneProblem = postalZoneMismatch(stateName, pincode);

  const summary = value.point
    ? `${value.point.lat.toFixed(5)}, ${value.point.lon.toFixed(5)}${
        value.point.accuracy !== undefined ? ` (±${Math.round(value.point.accuracy)} m)` : ""
      }`
    : "Not captured — media from this stage will be uploaded without a location";

  return (
    <section className="panel p-4" aria-labelledby={headingId}>
      <button
        type="button"
        className="flex w-full items-start justify-between gap-3 text-left"
        aria-expanded={open}
        onClick={() => setOpen((current) => !current)}
      >
        <span className="min-w-0">
          <span id={headingId} className="block font-display text-base font-bold text-ink-900">
            Where this stage is being recorded
          </span>
          <span className="mt-1 block text-sm leading-6 text-ink-muted">{summary}</span>
        </span>
        <ChevronDown className={`mt-1 h-5 w-5 shrink-0 text-ink-500 transition-transform ${open ? "rotate-180" : ""}`} aria-hidden />
      </button>

      {open ? (
        <div className="mt-4 grid gap-4">
          <p className="text-sm leading-6 text-ink-muted">
            Every photograph, recording and file attached anywhere on this stage is stamped with this place and time. It
            is captured only when you ask for it — a stage filled in at a desk a week later would otherwise stamp the
            desk onto photographs taken in a courtyard.
          </p>

          {/* CAPTURED AT — a measurement of where the device is. */}
          <div className="grid gap-2 rounded-md border border-line-200 bg-surface-50 p-3">
            <p className="field-label">Captured at — the device&apos;s own reading</p>
            <div className="flex flex-wrap items-center gap-2">
              <button type="button" className="field-button-secondary" disabled={disabled || busy} onClick={captureFix}>
                <LocateFixed className="h-4 w-4" aria-hidden />
                {busy ? "Getting a fix…" : value.point ? "Capture again" : "Use current GPS"}
              </button>
              {mapPickerAvailable() ? (
                <button
                  type="button"
                  className="field-button-secondary"
                  disabled={disabled}
                  aria-expanded={mapOpen}
                  onClick={() => setMapOpen((current) => !current)}
                >
                  <MapPinned className="h-4 w-4" aria-hidden />
                  {mapOpen ? "Hide the map" : "Pick on map"}
                </button>
              ) : null}
              {value.point ? (
                <button
                  type="button"
                  className="text-xs font-medium text-ink-500 underline"
                  disabled={disabled}
                  onClick={() => setPoint(null, undefined)}
                >
                  Clear
                </button>
              ) : null}
            </div>
            {mapOpen ? (
              <MapPointPicker
                lat={value.point?.lat ?? null}
                lon={value.point?.lon ?? null}
                markerColor="#64748b"
                onPick={(lat, lon) =>
                  // A hand-placed pin measured nothing and happened at no particular instant, so it
                  // carries no accuracy — and the capture time is the moment it was placed, which is
                  // the only honest answer available.
                  setPoint({ lat: Number(lat), lon: Number(lon) }, new Date().toISOString())
                }
                onFailure={() => setProblem("The map could not be loaded. Use the GPS button instead.")}
                ariaLabel="Click the map to say where this stage is being recorded"
              />
            ) : null}
            {problem ? <p className="text-xs font-medium leading-5 text-error-600">{problem}</p> : null}
          </div>

          {/* STATED — the designer's answer about the place, from the same closed lists the API
              validates against. Only usable alongside a coordinate, and the notice says so rather
              than letting somebody fill four boxes that go nowhere. */}
          <div className="grid gap-3 rounded-md border border-line-200 bg-card p-3">
            <p className="field-label">The place itself</p>
            <div className="grid gap-3 md:grid-cols-2">
              <div className="grid min-w-0 gap-1">
                <span className="field-label" id={`${headingId}-state`}>
                  State or union territory
                </span>
                <Dropdown
                  value={stateName}
                  onChange={(next) => setStated({ state: next, district: "" })}
                  options={stateOptions}
                  placeholder="Select"
                  /* Reference data, so `searchable` is passed by provenance rather than left to the
                     option count — the rule on `SearchableSelectProps.searchable`. No behaviour
                     change here (the state list is always past eight); it keeps this box and the
                     district beside it under one rule, and the district's is a real fix. */
                  searchable
                  ariaLabel="State or union territory"
                  disabled={disabled}
                />
              </div>
              <div className="grid min-w-0 gap-1">
                <span className="field-label">District</span>
                <Dropdown
                  value={district}
                  onChange={(next) => setStated({ district: next })}
                  options={districtOptions}
                  placeholder={stateName ? "Select" : "Choose a state first"}
                  /* ── `searchable` HERE IS A FIX ───────────────────────────────────────────────
                     795 district names, and per state the list straddles `SEARCH_THRESHOLD` both
                     ways: Goa 2, Sikkim 6, Uttar Pradesh 75. Left to the count, this box grew a
                     filter box for one state and lost it for the next on the same card. */
                  searchable
                  ariaLabel="District"
                  disabled={disabled || !districtOptions.length}
                />
                {stateName && !districtOptions.length ? (
                  // Said rather than left blank: 795 districts cannot be bundled, so an empty list
                  // means the reference fetch has not landed, not that the state has no districts.
                  <p className="text-xs leading-5 text-ink-500">
                    The district list has not arrived — it needs a connection. The rest of the card still works.
                  </p>
                ) : null}
              </div>
              <div className="grid min-w-0 gap-1">
                <label className="field-label" htmlFor={`${headingId}-village`}>
                  Village or town
                </label>
                <input
                  id={`${headingId}-village`}
                  className="field-input"
                  type="text"
                  value={village}
                  disabled={disabled}
                  onChange={(event) => setStated({ village: event.target.value })}
                />
              </div>
              <div className="grid min-w-0 gap-1">
                <label className="field-label" htmlFor={`${headingId}-pincode`}>
                  Pincode
                </label>
                <input
                  id={`${headingId}-pincode`}
                  className="field-input"
                  type="text"
                  inputMode="numeric"
                  maxLength={6}
                  value={pincode}
                  disabled={disabled}
                  onChange={(event) => setStated({ pincode: event.target.value.replace(/\D/g, "").slice(0, 6) })}
                />
                {pincodeProblem ? <p className="text-xs font-medium leading-5 text-error-600">{pincodeProblem}</p> : null}
                {/* Advisory, never blocking: it is a postal-zone check, so it can prove a
                    contradiction but never confirm a code, and a field app that refused to save over
                    a heuristic loses the day's work at the edge of coverage. */}
                {!pincodeProblem && zoneProblem ? (
                  <p className="rounded-md border border-amber-500 bg-amber-100 px-2 py-1 text-xs leading-5 text-amber-800">
                    {zoneProblem}
                  </p>
                ) : null}
              </div>
            </div>
            {!value.point && (stateName || district || village || pincode) ? (
              <p className="rounded-md border border-amber-500 bg-amber-100 px-2 py-1 text-xs leading-5 text-amber-800">
                These are stored alongside the coordinates, so they need a captured point above before they can be saved
                with the media. Capture a fix or drop a pin, or clear these boxes.
              </p>
            ) : null}
          </div>
        </div>
      ) : null}
    </section>
  );
}

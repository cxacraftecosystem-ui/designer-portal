"use client";

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
import { ActionGroup } from "@/components/ui/ActionGroup";

export type StageRecordingPlace = {
  point: DwGeoValue | null;
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
      });
    return () => {
      live = false;
    };
  }, []);

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
      setProblem("No location from this browser. Use the map.");
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
            ...(Number.isFinite(position.coords.accuracy) ? { accuracy: position.coords.accuracy } : {})
          },
          new Date().toISOString()
        );
      },
      (failure) => {
        setBusy(false);
        setProblem(
          failure.code === failure.PERMISSION_DENIED
            ? "Location blocked. Allow it for this site, or use the map."
            : failure.code === failure.POSITION_UNAVAILABLE
              ? "No location — turn on location services."
              : "No fix in time. Try outdoors, or use the map."
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
    : "No location yet";

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
            Stamped on every file in this stage. Captured only when you ask.
          </p>

          <div className="grid gap-2 rounded-md border border-line-200 bg-surface-50 p-3">
            <p className="field-label">Captured at — the device&apos;s own reading</p>
            <ActionGroup>
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
            </ActionGroup>
            {mapOpen ? (
              <MapPointPicker
                lat={value.point?.lat ?? null}
                lon={value.point?.lon ?? null}
                markerColor="#64748b"
                onPick={(lat, lon) =>
                  setPoint({ lat: Number(lat), lon: Number(lon) }, new Date().toISOString())
                }
                onFailure={() => setProblem("Map didn't load. Use GPS instead.")}
                ariaLabel="Click the map to set the location"
              />
            ) : null}
            {problem ? <p className="text-xs font-medium leading-5 text-error-600">{problem}</p> : null}
          </div>

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
                  searchable
                  ariaLabel="District"
                  disabled={disabled || !districtOptions.length}
                />
                {stateName && !districtOptions.length ? (
                  <p className="text-xs leading-5 text-ink-500">
                    Districts need a connection.
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
                {!pincodeProblem && zoneProblem ? (
                  <p className="rounded-md border border-amber-500 bg-amber-100 px-2 py-1 text-xs leading-5 text-amber-800">
                    {zoneProblem}
                  </p>
                ) : null}
              </div>
            </div>
            {!value.point && (stateName || district || village || pincode) ? (
              <p className="rounded-md border border-amber-500 bg-amber-100 px-2 py-1 text-xs leading-5 text-amber-800">
                Capture a location or drop a pin first, or clear these.
              </p>
            ) : null}
          </div>
        </div>
      ) : null}
    </section>
  );
}

"use client";

import { useEffect, useRef, useState } from "react";
import { ImageIcon, LocateFixed, MapPinned } from "lucide-react";

import { MapPointPicker, mapPickerAvailable } from "@/components/forms/MapPointPicker";
import { resolvePoint, type PointAddress } from "@/components/forms/LocationFields";
import { ActionGroup } from "@/components/ui/ActionGroup";
import { geoValue, readNumber, type DwGeoValue, type DwValue } from "@/lib/designWorkshops";
import { photoPosition, type PhotoPosition } from "@/lib/photoPosition";

const FIX_TIMEOUT_MS = 30_000;

export function StageGeoField({
  labelId,
  value,
  onChange,
  disabled,
  recordingPlace
}: {
  labelId: string;
  value: DwValue | undefined;
  onChange: (value: DwValue) => void;
  disabled?: boolean;
  recordingPlace?: DwGeoValue | null;
}) {
  const stored = geoValue(value);
  const [lat, setLat] = useState(() => (stored ? String(stored.lat) : ""));
  const [lon, setLon] = useState(() => (stored ? String(stored.lon) : ""));
  const [busy, setBusy] = useState(false);
  const [problem, setProblem] = useState<string | null>(null);
  const [mapOpen, setMapOpen] = useState(false);
  const [place, setPlace] = useState<PointAddress | null>(null);
  const [placeLooking, setPlaceLooking] = useState(false);
  const [fromPhoto, setFromPhoto] = useState<PhotoPosition | null>(null);
  const photoInput = useRef<HTMLInputElement | null>(null);

  async function readPhoto(file: File | undefined) {
    if (!file) return;
    setProblem(null);
    const found = await photoPosition(file);
    if (photoInput.current) photoInput.current.value = "";
    if (!found) {
      setFromPhoto(null);
      setProblem("That photo has no location.");
      return;
    }
    setFromPhoto(found);
  }

  const storedKey = stored ? `${stored.lat},${stored.lon}` : "";
  const lookup = useRef<AbortController | null>(null);
  useEffect(() => {
    lookup.current?.abort();
    setPlace(null);
    if (!storedKey || !mapPickerAvailable()) return;
    const [latText, lonText] = storedKey.split(",");
    const controller = new AbortController();
    lookup.current = controller;
    setPlaceLooking(true);
    resolvePoint(latText, lonText, controller.signal)
      .then((found) => {
        if (controller.signal.aborted) return;
        setPlace(found);
      })
      .catch(() => {
      })
      .finally(() => {
        if (!controller.signal.aborted) setPlaceLooking(false);
      });
    return () => controller.abort();
  }, [storedKey]);

  useEffect(() => () => lookup.current?.abort(), []);

  function commit(nextLat: string, nextLon: string, accuracy?: number) {
    const latNumber = readNumber(nextLat);
    const lonNumber = readNumber(nextLon);
    if (latNumber === null || lonNumber === null) {
      if (stored) onChange(null);
      return;
    }
    if (latNumber < -90 || latNumber > 90 || lonNumber < -180 || lonNumber > 180) {
      setProblem("Latitude is -90 to 90; longitude -180 to 180.");
      return;
    }
    setProblem(null);
    onChange({
      lat: latNumber,
      lon: lonNumber,
      ...(accuracy !== undefined && Number.isFinite(accuracy) ? { accuracy } : {})
    });
  }

  function useDeviceFix() {
    if (typeof navigator === "undefined" || !navigator.geolocation) {
      setProblem("No location from this browser. Type coordinates or use the map.");
      return;
    }
    setBusy(true);
    setProblem(null);
    navigator.geolocation.getCurrentPosition(
      (position) => {
        setBusy(false);
        const nextLat = String(position.coords.latitude);
        const nextLon = String(position.coords.longitude);
        setLat(nextLat);
        setLon(nextLon);
        commit(nextLat, nextLon, position.coords.accuracy);
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

  function pick(nextLat: string, nextLon: string) {
    setLat(nextLat);
    setLon(nextLon);
    commit(nextLat, nextLon);
  }

  return (
    <div className="grid gap-2 rounded-md border border-line-200 bg-surface-50 p-3" role="group" aria-labelledby={labelId}>
      <div className="grid gap-2 sm:grid-cols-2">
        <input
          className="field-input"
          type="text"
          inputMode="decimal"
          aria-label="Latitude"
          placeholder="Latitude"
          value={lat}
          disabled={disabled}
          onChange={(event) => setLat(event.target.value)}
          onBlur={(event) => commit(event.target.value, lon)}
        />
        <input
          className="field-input"
          type="text"
          inputMode="decimal"
          aria-label="Longitude"
          placeholder="Longitude"
          value={lon}
          disabled={disabled}
          onChange={(event) => setLon(event.target.value)}
          onBlur={(event) => commit(lat, event.target.value)}
        />
      </div>

      <ActionGroup align="start">
        <button type="button" className="field-button-secondary" disabled={disabled || busy} onClick={useDeviceFix}>
          <LocateFixed className="h-4 w-4" aria-hidden />
          {busy ? "Getting a fix…" : "Use current GPS"}
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
        <button
          type="button"
          className="field-button-secondary"
          disabled={disabled}
          onClick={() => photoInput.current?.click()}
        >
          <ImageIcon className="h-4 w-4" aria-hidden />
          From a photo
        </button>
      </ActionGroup>
      <input
        ref={photoInput}
        type="file"
        accept="image/*"
        className="hidden"
        onChange={(event) => void readPhoto(event.target.files?.[0])}
      />
      {fromPhoto ? (
        <div className="flex flex-col gap-2 rounded-md border border-line-200 bg-surface-50 px-3 py-2 sm:flex-row sm:items-center">
          <p className="min-w-0 flex-1 text-xs leading-5 text-ink-700">
            {fromPhoto.lat.toFixed(5)}, {fromPhoto.lon.toFixed(5)}
            {fromPhoto.takenAt ? ` · taken ${fromPhoto.takenAt}` : ""}
          </p>
          <ActionGroup inline align="end" className="max-sm:w-full">
            <button
              type="button"
              className="field-button-secondary"
              disabled={disabled}
              onClick={() => {
                setLat(String(fromPhoto.lat));
                setLon(String(fromPhoto.lon));
                commit(String(fromPhoto.lat), String(fromPhoto.lon));
                setFromPhoto(null);
              }}
            >
              Use this location
            </button>
          </ActionGroup>
        </div>
      ) : null}

      <div className="flex flex-wrap items-center gap-x-3 gap-y-1 empty:hidden">
        {recordingPlace ? (
          <button
            type="button"
            className="text-xs font-medium text-purple-700 dark:text-purple-300 underline"
            disabled={disabled}
            onClick={() => {
              setLat(String(recordingPlace.lat));
              setLon(String(recordingPlace.lon));
              commit(String(recordingPlace.lat), String(recordingPlace.lon), recordingPlace.accuracy);
            }}
          >
            Use the place this stage was recorded
          </button>
        ) : null}

        {stored ? (
          <button
            type="button"
            className="text-xs font-medium text-ink-500 underline"
            disabled={disabled}
            onClick={() => {
              setLat("");
              setLon("");
              setPlace(null);
              onChange(null);
            }}
          >
            Remove pin
          </button>
        ) : null}

        {stored?.accuracy !== undefined ? (
          <span className="text-xs text-ink-500">Accurate to about {Math.round(stored.accuracy)} m</span>
        ) : null}
      </div>

      {mapOpen ? (
        <MapPointPicker
          lat={stored?.lat ?? null}
          lon={stored?.lon ?? null}
          onPick={pick}
          onFailure={() => setProblem("Map didn't load. Type coordinates or use GPS.")}
          ariaLabel="Click the map to place this location"
        />
      ) : null}

      {stored && (placeLooking || place) ? (
        <p className="text-xs leading-5 text-ink-500">
          {placeLooking
            ? "Checking where this point is…"
            : place && (place.state || place.district)
              ? `In ${[place.district, place.state].filter(Boolean).join(", ")}${
                  place.pincode ? ` (${place.pincode})` : ""
                }.`
              : "No known place here. Are latitude and longitude swapped?"}
        </p>
      ) : null}

      {problem ? <p className="text-xs font-medium leading-5 text-error-600">{problem}</p> : null}
    </div>
  );
}

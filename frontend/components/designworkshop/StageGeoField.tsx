"use client";

/**
 * A GEO field of a stage: a venue, a cluster, a workshop location.
 *
 * FOUR WAYS TO ANSWER IT, and a field app needs all four because each of the other three fails
 * somewhere this app is used:
 *
 *   1. **Type the two numbers.** The only one that works with no map tiles, no satellites and no
 *      network, and therefore the one that can never be taken away. It is also the keyboard route to
 *      a value the map can only express with a pointer.
 *   2. **Use the device's fix.** One tap, and it carries its own accuracy radius — which travels
 *      with the coordinate, because a point whose error radius is unknown is indistinguishable from
 *      a point that is wrong.
 *   3. **Point at it on the map.** The real MapLibre picker the location card uses, loaded from the
 *      shared module so the megabyte is downloaded once per session rather than once per surface.
 *   4. **Copy the stage's place of recording**, when one has been captured. A venue location and the
 *      place the stage was recorded are usually the same point, and re-answering it is the kind of
 *      friction that ends with the field left blank.
 *
 * WHAT IT SAYS BACK. Whatever the point is, the card asks the SAME closed lists the rest of the app
 * validates addresses against — `GET /reference/address` for the 36 states and 795 districts, and
 * MapTiler for the point — and prints the state and district it lands in. That readout is not stored
 * anywhere and is not meant to be: it exists so a designer can see, before they move on, that the
 * coordinate they just entered is in Rajasthan and not in the Bay of Bengal. A transposed latitude
 * and longitude is silent as two numbers and obvious as "West Bengal".
 *
 * ⚠ `region` IS THE STATE AND `subregion` IS THE DISTRICT in MapTiler's Indian hierarchy. `county`
 * is the trap — it answers "Sanganer Tehsil" for Bagru: a real name at the wrong administrative
 * level, plausible enough to be believed. This card does not choose between them; it calls
 * `resolvePoint`, which is the function the artisan form's location card uses and which was measured
 * against sixteen real coordinates.
 */

import { useEffect, useRef, useState } from "react";
import { LocateFixed, MapPinned } from "lucide-react";

import { MapPointPicker, mapPickerAvailable } from "@/components/forms/MapPointPicker";
import { resolvePoint, type PointAddress } from "@/components/forms/LocationFields";
import { geoValue, readNumber, type DwGeoValue, type DwValue } from "@/lib/designWorkshops";

/** Shorter than the location card's own hunt: somebody is standing there with a finger on a button. */
const FIX_TIMEOUT_MS = 30_000;

export function StageGeoField({
  labelId,
  value,
  onChange,
  disabled,
  /** The stage's place of recording, when the card at the top of the page has captured one. */
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

  /*
   * THERE IS DELIBERATELY NO EFFECT RE-SEEDING THESE TWO BOXES FROM `value`.
   *
   * It is unnecessary — the collection editor unmounts a row's panel when it collapses and keys each
   * row by its client key, and the stage form keys each entity, so this component genuinely remounts
   * whenever the record underneath it changes. And it is actively harmful: the commit below clears
   * the stored value when either half stops parsing, so a re-seeding effect would fire on that clear
   * and wipe the half-typed latitude out of the box the designer is still typing into.
   */

  /**
   * Ask the closed lists where the stored point is. Read-only, always — see the file header.
   *
   * Keyed on the STORED coordinate rather than on the two text boxes, so a half-typed latitude does
   * not fire a geocode per keystroke across a metered rural connection.
   */
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
        // Offline is the normal weather here and this readout is a sanity check, not a gate. It
        // simply does not appear, which is honest: nothing claims a place that was never looked up.
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
    // The same bounds the server's `coerce_value` enforces. Checking here means a transposed pair is
    // refused at the keyboard rather than at Save, forty fields later.
    if (latNumber < -90 || latNumber > 90 || lonNumber < -180 || lonNumber > 180) {
      setProblem("A latitude runs from -90 to 90 and a longitude from -180 to 180.");
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
      setProblem("This browser cannot report a location. Type the coordinates in, or point at the place on the map.");
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
        // The three refusals are kept apart because the next move differs: unblock the browser, turn
        // location on, or go outside. "Location failed" tells a designer none of the three.
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

  function pick(nextLat: string, nextLon: string) {
    setLat(nextLat);
    setLon(nextLon);
    // A hand-placed pin carries no accuracy and must not inherit the previous fix's: the researcher
    // pointed at the place, and a pointer has no error radius. Reusing the old radius would attach a
    // measurement to a point that was never measured.
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

      <div className="flex flex-wrap items-center gap-2">
        <button type="button" className="field-button-secondary" disabled={disabled || busy} onClick={useDeviceFix}>
          <LocateFixed className="h-4 w-4" aria-hidden />
          {busy ? "Getting a fix…" : "Use current GPS"}
        </button>

        {/* Offered only when the build has a map key. A "Pick on map" button that opens onto a grey
            rectangle reads as a broken app rather than as an unconfigured tenant. */}
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

        {recordingPlace ? (
          <button
            type="button"
            className="text-xs font-medium text-purple-700 underline"
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
          onFailure={() => setProblem("The map could not be loaded. You can still type the coordinates in, or use the GPS.")}
          ariaLabel="Click the map to place this location"
        />
      ) : null}

      {/* The sanity readout. Never stored, and it says which half it could not resolve rather than
          printing a confident half-answer: an empty district under a named state is a real result
          out here, not a failure. */}
      {stored && (placeLooking || place) ? (
        <p className="text-xs leading-5 text-ink-500">
          {placeLooking
            ? "Checking where this point is…"
            : place && (place.state || place.district)
              ? `This point is in ${[place.district, place.state].filter(Boolean).join(", ")}${
                  place.pincode ? ` (${place.pincode})` : ""
                }. Nothing here is saved — it is a check that the coordinates are where you meant.`
              : "The address lists do not recognise anywhere at these coordinates. Check the latitude and longitude are not the wrong way round."}
        </p>
      ) : null}

      {problem ? <p className="text-xs font-medium leading-5 text-error-600">{problem}</p> : null}
    </div>
  );
}

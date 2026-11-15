"use client";

import { useEffect, useState } from "react";

import { TimeField } from "@/components/forms/DateTimeField";
import { Toggle } from "@/components/settings/Toggle";
import { apiFetch } from "@/lib/api";

type AppSettings = {
  transcriptionMode: string;
  batchWindowEnabled: boolean;
  batchWindowStart: string;
  batchWindowEnd: string;
  batchTimezone: string;
};

function windowOnly({ batchWindowEnabled, batchWindowStart, batchWindowEnd, batchTimezone }: AppSettings) {
  return { batchWindowEnabled, batchWindowStart, batchWindowEnd, batchTimezone };
}

function minutesOfDay(value: string) {
  const [hours, minutes] = value.split(":");
  return Number(hours) * 60 + Number(minutes);
}

function windowSummary(start: string, end: string) {
  if (!/^\d{2}:\d{2}$/.test(start) || !/^\d{2}:\d{2}$/.test(end)) return null;
  if (start === end) return "Start and end are the same, so work runs at any time.";
  const span = (minutesOfDay(end) - minutesOfDay(start) + 1440) % 1440;
  const hours = Math.floor(span / 60);
  const minutes = span % 60;
  const length = [hours ? `${hours} hour${hours === 1 ? "" : "s"}` : null, minutes ? `${minutes} minutes` : null]
    .filter(Boolean)
    .join(" ");
  return minutesOfDay(end) < minutesOfDay(start)
    ? `${length} overnight, ${start} to ${end} the next morning.`
    : `${length}, ${start} to ${end}.`;
}

export function AppSettingsPanel() {
  const [settings, setSettings] = useState<AppSettings | null>(null);
  const [saving, setSaving] = useState(false);
  const [success, setSuccess] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  const [loadFailed, setLoadFailed] = useState(false);
  const [attempt, setAttempt] = useState(0);

  useEffect(() => {
    let cancelled = false;
    apiFetch<AppSettings>("/settings")
      .then((loaded) => {
        if (cancelled) return;
        setSettings(loaded);
        setLoadFailed(false);
        setError(null);
      })
      .catch(() => {
        if (cancelled) return;
        setLoadFailed(true);
      });
    return () => {
      cancelled = true;
    };
  }, [attempt]);

  async function save(event: React.FormEvent) {
    event.preventDefault();
    if (!settings) return;
    setSaving(true);
    setSuccess(null);
    setError(null);
    try {
      const updated = await apiFetch<AppSettings>("/settings", { method: "PUT", body: JSON.stringify(windowOnly(settings)) });
      setSettings(updated);
      setSuccess("Settings saved");
    } catch (err) {
      setError(err instanceof Error ? err.message : "Unable to save settings");
    } finally {
      setSaving(false);
    }
  }

  return (
    <div className="grid gap-4">
      {error ? (
        <div className="rounded-md border border-red-200 bg-error-100 px-3 py-2 text-sm text-red-800">{error}</div>
      ) : null}
      {success ? (
        <div className="rounded-md border border-emerald-200 bg-success-100 px-3 py-2 text-sm text-success-600">{success}</div>
      ) : null}
      {!settings && loadFailed ? (
        <div role="alert" className="panel flex flex-wrap items-center justify-between gap-3 p-4">
          <p className="text-sm text-red-700 dark:text-red-400">
            The settings could not be read, so nothing here can be changed yet.
          </p>
          <button
            type="button"
            className="field-button-secondary"
            onClick={() => {
              setLoadFailed(false);
              setAttempt((n) => n + 1);
            }}
          >
            Try again
          </button>
        </div>
      ) : !settings ? (
        <div className="panel p-4 text-sm text-ink-500">Loading settings...</div>
      ) : (
        <form className="grid gap-4" onSubmit={save}>
          <section className="panel p-5">
            <div className="flex items-start justify-between gap-4">
              <div>
                <h2 className="font-display font-bold text-ink-900">Process during an off-peak window</h2>
                <p className="mt-1 text-xs leading-5 text-ink-500">
                  When on, transcription &amp; refinement run only between the times below, so the heavy work happens when
                  nobody is uploading. When off, they run immediately.
                </p>
              </div>
              <Toggle
                checked={settings.batchWindowEnabled}
                label="Process during an off-peak window"
                onChange={(next) => setSettings({ ...settings, batchWindowEnabled: next })}
              />
            </div>
            <div className="mt-4 grid gap-3 sm:grid-cols-3">
              <div className="grid gap-1.5">
                <label className="field-label" htmlFor="batch-window-start">
                  Window start
                </label>
                <TimeField
                  id="batch-window-start"
                  disabled={!settings.batchWindowEnabled}
                  onChange={(batchWindowStart) => setSettings({ ...settings, batchWindowStart })}
                  value={settings.batchWindowStart}
                />
              </div>
              <div className="grid gap-1.5">
                <label className="field-label" htmlFor="batch-window-end">
                  Window end
                </label>
                <TimeField
                  id="batch-window-end"
                  disabled={!settings.batchWindowEnabled}
                  onChange={(batchWindowEnd) => setSettings({ ...settings, batchWindowEnd })}
                  value={settings.batchWindowEnd}
                />
              </div>
              <div>
                <label className="field-label" htmlFor="batch-timezone">
                  Timezone
                </label>
                <input
                  className="field-input mt-1.5"
                  disabled={!settings.batchWindowEnabled}
                  id="batch-timezone"
                  onChange={(event) => setSettings({ ...settings, batchTimezone: event.target.value })}
                  placeholder="Asia/Kolkata"
                  type="text"
                  value={settings.batchTimezone}
                />
              </div>
            </div>
            {settings.batchWindowEnabled ? (
              <p className="mt-2 text-xs leading-5 text-ink-500">
                {windowSummary(settings.batchWindowStart, settings.batchWindowEnd)}
              </p>
            ) : null}
          </section>

          <div>
            <button className="field-button" disabled={saving} type="submit">
              {saving ? "Saving..." : "Save settings"}
            </button>
          </div>
        </form>
      )}
    </div>
  );
}

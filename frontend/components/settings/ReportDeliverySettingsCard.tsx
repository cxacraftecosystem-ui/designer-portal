"use client";

import { useEffect, useState } from "react";
import { Send, X } from "lucide-react";

import { ActionGroup } from "@/components/ui/ActionGroup";
import { describeApiDetail } from "@/lib/api";
import {
  fetchReportDeliverySettings,
  saveReportDeliverySettings,
  type ReportDeliverySettings
} from "@/lib/designWorkshopApprovals";

/** Split typed text into addresses: commas, semicolons, spaces and new lines all separate. */
export function splitAddresses(text: string): string[] {
  return text
    .split(/[\s,;]+/)
    .map((part) => part.trim().toLowerCase())
    .filter(Boolean);
}

function problemOf(error: unknown, fallback: string): string {
  const detail = (error as { payload?: { detail?: unknown } } | null)?.payload?.detail;
  if (detail !== undefined) return describeApiDetail(detail, fallback);
  return error instanceof Error && error.message ? error.message : fallback;
}

/**
 * WHERE "APPROVE AND HAND ON" E-MAILS THE APPROVED REPORT — an administrator's setting (2026-10-18,
 * owner decision C4). No address is written into the product: with none set, the approver hands the
 * report on by hand and is told to ask for these. Saved whole, so removing the last address turns
 * e-mailed delivery off.
 */
export function ReportDeliverySettingsCard() {
  const [state, setState] = useState<ReportDeliverySettings | null>(null);
  const [addresses, setAddresses] = useState<string[]>([]);
  const [officeName, setOfficeName] = useState("");
  const [draft, setDraft] = useState("");
  const [saving, setSaving] = useState(false);
  const [message, setMessage] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let live = true;
    fetchReportDeliverySettings()
      .then((answer) => {
        if (!live) return;
        setState(answer);
        setAddresses(answer.addresses);
        setOfficeName(answer.officeName);
      })
      .catch(() => {
        if (live) setError("Couldn't load the office addresses.");
      });
    return () => {
      live = false;
    };
  }, []);

  function add() {
    const next = splitAddresses(draft).filter((address) => !addresses.includes(address));
    if (next.length) setAddresses((current) => [...current, ...next]);
    setDraft("");
  }

  async function save() {
    const pending = splitAddresses(draft).filter((address) => !addresses.includes(address));
    const list = [...addresses, ...pending];
    setSaving(true);
    setError(null);
    setMessage(null);
    try {
      const answer = await saveReportDeliverySettings({ addresses: list, officeName: officeName.trim() || null });
      setState(answer);
      setAddresses(answer.addresses);
      setOfficeName(answer.officeName);
      setDraft("");
      setMessage(answer.addresses.length ? "Saved." : "Saved. Reports will be handed on by hand.");
    } catch (err) {
      setError(problemOf(err, "Couldn't save the addresses."));
    } finally {
      setSaving(false);
    }
  }

  return (
    <section id="report-delivery" className="panel scroll-mt-28 p-5" aria-labelledby="report-delivery-heading">
      <div className="flex items-center gap-2.5">
        <span className="grid h-8 w-8 place-items-center rounded-md bg-purple-950 text-purple-100">
          <Send className="h-4 w-4" aria-hidden />
        </span>
        <h2 id="report-delivery-heading" className="font-display font-bold text-ink-900">
          Approved reports to the office
        </h2>
      </div>
      <p className="mt-1.5 text-xs leading-5 text-ink-500">
        “Approve and hand on” e-mails these addresses a download link that lasts 7 days.
      </p>
      {state && !state.mailAvailable ? (
        <p className="mt-2 text-xs leading-5 text-amber-800">E-mail isn&apos;t set up on this server yet.</p>
      ) : null}

      <div className="mt-3 grid gap-3 md:grid-cols-2">
        <label className="grid gap-1">
          <span className="field-label">Office name</span>
          <input
            className="field-input"
            value={officeName}
            maxLength={200}
            placeholder={state?.defaultOfficeName ?? ""}
            onChange={(event) => setOfficeName(event.target.value)}
          />
        </label>
        <div className="grid gap-1">
          <label className="field-label" htmlFor="report-delivery-address">
            Add an address
          </label>
          <div className="flex gap-2">
            <input
              id="report-delivery-address"
              className="field-input min-w-0 flex-1"
              type="email"
              inputMode="email"
              autoComplete="off"
              value={draft}
              placeholder="dc-office@example.gov.in"
              onChange={(event) => setDraft(event.target.value)}
              onKeyDown={(event) => {
                if (event.key === "Enter") {
                  event.preventDefault();
                  add();
                }
              }}
            />
            <button type="button" className="field-button-secondary" disabled={!draft.trim()} onClick={add}>
              Add
            </button>
          </div>
        </div>
      </div>

      {addresses.length ? (
        <ul className="mt-3 flex flex-wrap gap-2" aria-label="Office addresses">
          {addresses.map((address) => (
            <li
              key={address}
              className="inline-flex items-center gap-1 rounded-full border border-line-200 bg-surface-50 py-1 pl-3 pr-1 text-sm text-ink-900"
            >
              {address}
              <button
                type="button"
                className="grid h-7 w-7 place-items-center rounded-full text-ink-500 hover:bg-purple-50 hover:text-purple-700"
                aria-label={`Remove ${address}`}
                onClick={() => setAddresses((current) => current.filter((other) => other !== address))}
              >
                <X className="h-3.5 w-3.5" aria-hidden />
              </button>
            </li>
          ))}
        </ul>
      ) : (
        <p className="mt-3 text-sm text-ink-500">No addresses yet.</p>
      )}

      <ActionGroup className="mt-4" align="start">
        <button type="button" className="field-button" disabled={saving || !state} onClick={() => void save()}>
          {saving ? "Saving…" : "Save addresses"}
        </button>
      </ActionGroup>
      <p className="pt-2 text-xs leading-5" role="status">
        {error ? <span className="text-error-600">{error}</span> : <span className="text-ink-500">{message}</span>}
      </p>
    </section>
  );
}

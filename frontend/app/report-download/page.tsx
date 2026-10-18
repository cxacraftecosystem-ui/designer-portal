"use client";

/**
 * /report-download — the office opening an approved report it was e-mailed (2026-10-18).
 *
 * PUBLIC, LIKE /set-password: the Development Commissioner (Handicrafts) office has no account. The
 * link's token is the credential; it is read from the address in an effect and then taken OUT of the
 * address bar (`takeLinkTokenFromAddress`, the set-password rule), and travels to the server in a
 * request body, never on a request line. The download is a five-minute signed URL fetched on the
 * press, so a page left open does not hold a usable file address.
 */

import { useEffect, useState } from "react";
import { Download, FileText } from "lucide-react";

import { WorkshopLogo } from "@/components/WorkshopLogo";
import { GLASS_PANEL, GlassSurface } from "@/components/ui/GlassSurface";
import { ApiError } from "@/lib/api";
import { lookupReportDownload, openReportDownload, type ReportDownloadInfo } from "@/lib/designWorkshopApprovals";
import { formatDateTime } from "@/lib/format";
import { takeLinkTokenFromAddress } from "@/lib/signIn";

function refusal(error: unknown): string {
  if (error instanceof ApiError && typeof error.message === "string" && error.message.trim()) {
    if (error.status === 404 || error.status === 410) return error.message;
  }
  return "Couldn't open the report. Check your connection and try again.";
}

export default function ReportDownloadPage() {
  const [token, setToken] = useState<string | null>(null);
  const [info, setInfo] = useState<ReportDownloadInfo | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    const taken = takeLinkTokenFromAddress(window.location.href);
    if (taken.address !== null) {
      const address = taken.address;
      // After every effect of this commit, so the router's own replaceState patch is in place.
      queueMicrotask(() => window.history.replaceState(null, "", address));
    }
    setToken((previous) => previous || taken.token);
  }, []);

  useEffect(() => {
    if (token === null) return;
    if (!token) {
      setError("This link is incomplete. Open the whole link from the e-mail.");
      return;
    }
    let live = true;
    lookupReportDownload(token)
      .then((answer) => {
        if (live) setInfo(answer);
      })
      .catch((err) => {
        if (live) setError(refusal(err));
      });
    return () => {
      live = false;
    };
  }, [token]);

  async function download() {
    if (!token) return;
    setBusy(true);
    setError(null);
    try {
      const answer = await openReportDownload(token);
      window.location.assign(answer.url);
    } catch (err) {
      setError(refusal(err));
    } finally {
      setBusy(false);
    }
  }

  return (
    <main className="relative flex min-h-dvh items-center justify-center overflow-hidden bg-bg-0 px-4 py-6 md:px-10">
      <div aria-hidden className="pointer-events-none absolute inset-0">
        <div className="absolute inset-0 grad-mesh opacity-60" />
      </div>
      <GlassSurface options={GLASS_PANEL} className="glass-card relative z-10 w-full max-w-md rounded-xl p-6 shadow-lg md:p-8">
        <div className="mb-6 flex flex-col items-center gap-2 text-center">
          <WorkshopLogo className="h-12 w-12 rounded-xl shadow-sm" />
          <h1 className="font-display text-2xl font-bold text-ink-900">Approved report</h1>
        </div>
        {error ? (
          <div role="alert" className="rounded-md border border-red-200 bg-error-100 px-3 py-3 text-sm leading-6 text-error-600">
            {error}
          </div>
        ) : null}
        {!error && !info ? <p className="text-center text-sm text-ink-500">Checking this link…</p> : null}
        {info ? (
          <div className="grid gap-4">
            <div className="flex items-start gap-3 rounded-md border border-line-200 bg-card p-3">
              <FileText className="mt-0.5 h-5 w-5 shrink-0 text-purple-700" aria-hidden />
              <div className="min-w-0">
                <p className="font-display text-sm font-bold text-ink-900">{info.workshopTitle || info.fileName}</p>
                <p className="truncate text-xs text-ink-500">{info.fileName}</p>
              </div>
            </div>
            <p className="text-xs leading-5 text-ink-500">
              Sent to {info.officeName}. The link works until {formatDateTime(info.expiresAt)}.
            </p>
            <button type="button" className="field-button w-full" disabled={busy} onClick={() => void download()}>
              <Download className="h-4 w-4" aria-hidden />
              {busy ? "Opening…" : "Download the report"}
            </button>
          </div>
        ) : null}
      </GlassSurface>
    </main>
  );
}

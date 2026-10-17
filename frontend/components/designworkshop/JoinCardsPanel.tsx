"use client";

/**
 * Join cards on the web: print one, show it once, print it on paper, and list and cancel the cards
 * printed for this workshop. The handset's `DwJoinCardsPanel` (WorkshopCodesScreen.kt), over the same
 * routes and under the same server rules — see `lib/joinCards.ts` for which rules those are and why
 * none of them is re-decided here.
 *
 * THE FRESH CARD LIVES IN STATE AND NOWHERE ELSE. The server keeps only a digest and the last four
 * characters, so the symbol drawn below is the only copy outside paper. It is not written to the
 * draft, to browser storage or to the address bar, and leaving the page loses it — which the card
 * itself says, in the same words as the handset.
 *
 * NOT ADMIN-GATED. Anybody who can open the workshop may print a SINGLE-USE card (three unused at a
 * time); a card for more than one person, and the number of days, are offered to an admin only, and
 * the server refuses them to anybody else in its own words. Somebody who serves on the workshop as
 * its inspector or a director is refused by the server too, and that sentence is shown as given.
 */

import { Toggle } from "@/components/ui/Toggle";
import { useCallback, useEffect, useMemo, useState } from "react";
import { Ban, Loader2, Printer, QrCode } from "lucide-react";

import { useAuth } from "@/components/AuthProvider";
import { ApiError } from "@/lib/api";
import {
  JOIN_CARD_DEFAULT_DAYS,
  JOIN_CARD_MAX_DAYS,
  joinCardFailure,
  joinCardState,
  listJoinCards,
  mintJoinCard,
  revokeJoinCard,
  type JoinCard
} from "@/lib/joinCards";
import { isAdmin } from "@/lib/permissions";
import { encodeQr, qrSvgPath } from "@/lib/qrEncode";
import { formatWorkshopCodeForPrint } from "@/lib/workshopCodes";

/** What the card says about itself — the handset's `designWorkshopJoinCardPurposeMessage`, verbatim. */
export const JOIN_CARD_PURPOSE =
  "Lets one person join this workshop. Treat it like a key — don't photograph or forward it. It expires after use or in a few weeks.";

/** The same purpose, for an admin's card good for several people. */
function purposeFor(card: JoinCard): string {
  if (card.maxUses === 1) return JOIN_CARD_PURPOSE;
  const who = card.maxUses === null ? "any number of people" : `up to ${card.maxUses} people`;
  return `Lets ${who} join this workshop. Treat it like a key — don't photograph or forward it. It expires on its date.`;
}

export const JOIN_CARD_SHOWN_ONCE =
  "Shown only once. Print or write it down now.";

type CardSymbol = { path: string; extent: number; printed: string } | null;

function drawCard(code: string): CardSymbol {
  try {
    const svg = qrSvgPath(encodeQr(code, "Q"));
    return { path: svg.path, extent: svg.extent, printed: formatWorkshopCodeForPrint(code) };
  } catch {
    return null;
  }
}

function escapeHtml(text: string): string {
  return text.replace(/[&<>"']/g, (character) => `&#${character.charCodeAt(0)};`);
}

/**
 * Print one card on its own sheet, from a window of its own, so the page's chrome and every other
 * card stay off the paper. Answers false when the browser blocked the window.
 */
function printCard(card: JoinCard, symbol: NonNullable<CardSymbol>, workshopTitle: string): boolean {
  const opened = window.open("", "_blank", "width=640,height=820");
  if (!opened) return false;
  opened.opener = null;
  const svg = `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${symbol.extent} ${symbol.extent}" shape-rendering="crispEdges" width="60mm" height="60mm"><rect width="${symbol.extent}" height="${symbol.extent}" fill="#fff"/><path d="${symbol.path}"/></svg>`;
  opened.document.write(`<!doctype html><html><head><meta charset="utf-8"><title>Join card</title>
<style>
  @page { size: A6 portrait; margin: 8mm; }
  body { font: 11pt/1.4 system-ui, sans-serif; color: #111; margin: 0; }
  .card { border: 0.3mm dashed #888; padding: 6mm; text-align: center; }
  h1 { font-size: 13pt; margin: 0 0 1mm; }
  .sub { font-size: 9pt; color: #444; margin: 0 0 4mm; }
  .code { font-family: ui-monospace, monospace; font-size: 10pt; letter-spacing: 0.3mm; margin: 3mm 0; word-break: break-all; }
  .note { font-size: 8pt; color: #333; text-align: left; }
</style></head><body><div class="card">
<h1>Join card</h1><p class="sub">${escapeHtml(workshopTitle)}${card.label ? ` · ${escapeHtml(card.label)}` : ""}</p>
${svg}
<p class="code">${escapeHtml(symbol.printed)}</p>
<p class="note">${escapeHtml(purposeFor(card))}</p>
</div></body></html>`);
  opened.document.close();
  opened.focus();
  opened.print();
  return true;
}

export function JoinCardsPanel({
  workshopId,
  workshopTitle,
  shareable
}: {
  /** The workshop's id on the server. */
  workshopId: string;
  workshopTitle: string;
  /** False for a workshop that exists only in this browser — there is nothing to print a card for. */
  shareable: boolean;
}) {
  const { user } = useAuth();
  const admin = isAdmin(user);

  const [cards, setCards] = useState<JoinCard[]>([]);
  const [truncated, setTruncated] = useState(false);
  const [listed, setListed] = useState(false);
  const [busy, setBusy] = useState(false);
  const [notice, setNotice] = useState<string | null>(null);
  /** The card just printed, SECRET INCLUDED. In memory only — see the header. */
  const [minted, setMinted] = useState<JoinCard | null>(null);

  const [label, setLabel] = useState("");
  const [people, setPeople] = useState("1");
  const [anyNumber, setAnyNumber] = useState(false);
  const [days, setDays] = useState(String(JOIN_CARD_DEFAULT_DAYS));

  /**
   * True when this account is not one that prints cards for this workshop — its inspector or a
   * director reading it, say. The list answers the ordinary 404 for them, and the panel then says who
   * does print cards instead of offering a button that would be refused.
   */
  const [notIssuer, setNotIssuer] = useState(false);

  const refresh = useCallback(async () => {
    try {
      const answer = await listJoinCards(workshopId);
      setCards(answer.grants);
      setTruncated(answer.truncated);
      setListed(true);
      setNotIssuer(false);
    } catch (error) {
      if (error instanceof ApiError && error.status === 404) {
        setNotIssuer(true);
        return;
      }
      setNotice(joinCardFailure(error).message);
    }
  }, [workshopId]);

  useEffect(() => {
    // A different workshop drops the previous one's fresh card: it is not this workshop's key.
    setMinted(null);
    setListed(false);
    setNotIssuer(false);
    setCards([]);
    if (shareable) void refresh();
  }, [shareable, refresh]);

  const symbol = useMemo(() => (minted?.code ? drawCard(minted.code) : null), [minted]);

  async function print() {
    if (busy) return;
    setBusy(true);
    setNotice(null);
    try {
      const wanted = Number.parseInt(people, 10);
      const wantedDays = Number.parseInt(days, 10);
      const card = await mintJoinCard(workshopId, {
        maxUses: admin ? (anyNumber ? null : Number.isFinite(wanted) && wanted >= 1 ? wanted : 1) : 1,
        daysValid: admin && Number.isFinite(wantedDays) ? wantedDays : null,
        label
      });
      setMinted(card);
      setLabel("");
      await refresh();
    } catch (error) {
      setNotice(joinCardFailure(error).message);
    } finally {
      setBusy(false);
    }
  }

  async function cancel(card: JoinCard) {
    if (busy) return;
    setBusy(true);
    setNotice(null);
    try {
      await revokeJoinCard(card.id);
      // A symbol left on screen for a card that no longer works is how somebody hands over a dead card.
      if (minted?.id === card.id) setMinted(null);
      await refresh();
    } catch (error) {
      setNotice(joinCardFailure(error).message);
    } finally {
      setBusy(false);
    }
  }

  return (
    <section className="rounded-md border border-line-200 bg-card" aria-labelledby="join-cards-heading" data-testid="join-cards-panel">
      <div className="flex items-start gap-2 p-3">
        <QrCode className="mt-0.5 h-4 w-4 shrink-0 text-ink-500" aria-hidden />
        <div className="min-w-0 flex-1">
          <h2 id="join-cards-heading" className="text-sm font-medium text-ink-900">
            Join cards
          </h2>
          <p className="mt-0.5 text-xs leading-5 text-ink-500">
            Print a card; one scan adds that person to this workshop.
          </p>
        </div>
      </div>

      <div className="grid gap-3 border-t border-line-200 p-3">
        {notIssuer ? (
          <p className="text-sm leading-6 text-ink-700">
            Ask a designer or admin to print a join card.
          </p>
        ) : !shareable ? (
          <p className="rounded-md border border-amber-500 bg-amber-100 px-3 py-2 text-sm leading-6 text-amber-800">
            Sync this workshop before printing a join card.
          </p>
        ) : (
          <>
            <div className="flex flex-wrap items-end gap-3">
              <label className="grid min-w-48 flex-1 gap-1 text-xs font-medium text-ink-700">
                Who it is for (optional)
                <input
                  className="field-input"
                  value={label}
                  maxLength={200}
                  onChange={(event) => setLabel(event.target.value)}
                  placeholder="Label (optional)"
                />
              </label>
              {admin ? (
                <>
                  <label className="grid w-32 gap-1 text-xs font-medium text-ink-700">
                    People it admits
                    <input
                      className="field-input"
                      type="number"
                      min={1}
                      max={1000}
                      value={people}
                      disabled={anyNumber}
                      onChange={(event) => setPeople(event.target.value)}
                    />
                  </label>
                  <Toggle label="Any number" checked={anyNumber} onChange={setAnyNumber} />
                  <label className="grid w-32 gap-1 text-xs font-medium text-ink-700">
                    Valid for (days)
                    <input
                      className="field-input"
                      type="number"
                      min={1}
                      max={JOIN_CARD_MAX_DAYS}
                      value={days}
                      onChange={(event) => setDays(event.target.value)}
                    />
                  </label>
                </>
              ) : null}
              <button type="button" className="field-button" onClick={() => void print()} disabled={busy} data-testid="join-card-print">
                {busy ? <Loader2 className="h-4 w-4 animate-spin" aria-hidden /> : <QrCode className="h-4 w-4" aria-hidden />}
                Print a join card
              </button>
            </div>
            {admin ? (
              <p className="text-xs leading-5 text-ink-500">
                Admins can make one card for a group.
              </p>
            ) : null}
          </>
        )}

        <div role="status" aria-live="polite">
          {notice ? (
            <p className="rounded-md border border-amber-500 bg-amber-100 px-3 py-2 text-sm leading-6 text-amber-800">{notice}</p>
          ) : null}
        </div>

        {minted?.code ? (
          <div className="grid justify-items-center gap-3 rounded-md border border-line-200 bg-surface-50 p-4 text-center" data-testid="join-card-fresh">
            {symbol ? (
              <svg
                viewBox={`0 0 ${symbol.extent} ${symbol.extent}`}
                xmlns="http://www.w3.org/2000/svg"
                shapeRendering="crispEdges"
                role="img"
                aria-label="Join card for this design workshop"
                className="h-56 w-56"
              >
                <rect width={symbol.extent} height={symbol.extent} fill="#ffffff" />
                <path d={symbol.path} />
              </svg>
            ) : (
              <p className="text-sm text-amber-800 dark:text-amber-100">
                Couldn&apos;t draw the symbol — the characters below work instead.
              </p>
            )}
            <p className="font-mono text-sm tracking-wide text-ink-900">{symbol?.printed ?? formatWorkshopCodeForPrint(minted.code)}</p>
            <p className="max-w-prose text-xs leading-5 text-ink-500">{purposeFor(minted)}</p>
            <p className="max-w-prose rounded-md border border-amber-500 bg-amber-100 px-3 py-2 text-xs leading-5 text-amber-800">
              {JOIN_CARD_SHOWN_ONCE}
            </p>
            {symbol ? (
              <button
                type="button"
                className="field-button-secondary"
                onClick={() => {
                  if (!printCard(minted, symbol, workshopTitle)) {
                    setNotice("Print window blocked. Allow pop-ups and try again.");
                  }
                }}
              >
                <Printer className="h-4 w-4" aria-hidden />
                Print this card
              </button>
            ) : null}
          </div>
        ) : null}

        {listed && cards.length ? (
          <div className="grid gap-2">
            <h3 className="text-xs font-medium text-ink-700">Cards printed for this workshop</h3>
            {truncated ? (
              <p className="rounded-md border border-amber-500 bg-amber-100 px-3 py-2 text-xs leading-5 text-amber-800">
                Not all cards are shown. Cancel unused ones.
              </p>
            ) : null}
            <ul className="grid gap-2">
              {cards.map((card) => (
                <li key={card.id} className="flex flex-wrap items-center gap-3 rounded-md border border-line-200 bg-surface-50 px-3 py-2" data-testid="join-card-row">
                  <div className="min-w-0 flex-1">
                    <div className="font-mono text-sm text-ink-900">…{card.secretLast4}</div>
                    <div className="text-xs leading-5 text-ink-500">{joinCardState(card)}</div>
                  </div>
                  {card.revokedAt ? null : (
                    <button type="button" className="field-button-secondary" disabled={busy} onClick={() => void cancel(card)}>
                      <Ban className="h-4 w-4" aria-hidden />
                      Cancel
                    </button>
                  )}
                </li>
              ))}
            </ul>
          </div>
        ) : null}
      </div>
    </section>
  );
}

"use client";

import { useCallback, useEffect, useState } from "react";

import {
  attemptKey,
  hasAttempted,
  isOnline,
  MEDIA_REFRESH_BUDGET,
  presignedUrlExpired,
  refreshesSpent,
  refreshMediaUrl,
  resolveFailedMediaUrl,
  type RefreshableMedia
} from "@/lib/mediaUrlRefresh";

/**
 * THE ONE PLACE A MEDIA URL IS TURNED INTO AN `src`, AND THE ONLY ONE THAT MAY RETRY.
 *
 * `lib/mediaUrlRefresh.ts` carries the whole argument — why URLs now expire, why a refresh may
 * happen once per url and only ever a bounded number of times per row, and what is irreducibly lost
 * offline. This is the thin React half.
 *
 * ── HOW A CALL SITE USES IT ─────────────────────────────────────────────────────────────────────
 *
 *     const { src, onError } = useMediaUrl(item);
 *     …
 *     {src ? <img src={src} onError={onError} … /> : null}
 *
 * `src` starts as whatever the row carries and is replaced only by a URL the server hands back on a
 * re-read of that row — at most `MEDIA_REFRESH_BUDGET` times, and once in the ordinary case where
 * the fresh link works. `onError` is safe to attach to `<img>`, `<video>`,
 * `<audio>` and `<iframe>` alike — none of them reports a status, which is precisely why the
 * classification happens in the module rather than here.
 *
 * ── TWO TRIGGERS, ONE LEDGER ────────────────────────────────────────────────────────────────────
 *
 *   * **Pre-emptive**, in an effect: a signed URL that is already past its own `X-Amz-Expires` is
 *     swapped BEFORE the element is asked to load it, so the reader never sees the broken frame.
 *     This is the case a cached payload produces — a store rehydrated from IndexedDB, a background
 *     tab, a workshop left open over lunch.
 *   * **Reactive**, from `onError`: everything else, classified by a one-byte probe.
 *
 * Both go through the module's shared ledger, in BOTH its layers, so a file that gets a pre-emptive
 * refresh and then still fails does NOT get a second one for the same url — and a row whose fresh
 * signatures keep failing stops at `MEDIA_REFRESH_BUDGET` however many new urls it is handed. The
 * pre-emptive arm below spends from the same purse and has to check it by hand: it does not go
 * through `refreshVerdict` (there is no failure yet to classify), so the guard cannot be inherited.
 *
 * ── THE RULES THIS HOOK IS WRITTEN AGAINST ──────────────────────────────────────────────────────
 *
 * **A null answer never clears what is on screen.** Every media component in this app already
 * renders a considered state for a row with no `url` (an entitlement answer, not an error), and
 * turning a working-but-broken image into that state would be a worse lie than the broken frame.
 * So `src` only ever moves forwards, to a URL the server actually gave us.
 *
 * **The effect owns a `cancelled` flag and nothing else.** There is no ref guard here on purpose —
 * the trap index's deadlock shape is an effect that claims a ref on start and releases it only on
 * completion, which `reactStrictMode: true` turns into a permanent stall. The loop guard lives in
 * the module's ledger, which is keyed by data rather than by lifecycle and therefore cannot be left
 * held by an attempt that was torn down.
 *
 * **`media.url` is the reset signal.** When the row's own URL changes — a re-fetched list, a
 * different file in the same slot — the local override is dropped and the new stored URL is drawn.
 * Keeping a stale override there is how one photograph ends up rendered under another's caption.
 */
export function useMediaUrl(media: RefreshableMedia | null | undefined): {
  src: string | null;
  onError: () => void;
} {
  const stored = media?.url ?? null;
  const mediaId = media?.id ?? null;
  const [fresh, setFresh] = useState<string | null>(null);

  // The row moved on: forget anything this hook minted for the previous URL.
  useEffect(() => {
    setFresh(null);
  }, [stored, mediaId]);

  // PRE-EMPTIVE. A cached signed URL that is already dead is replaced before it is drawn.
  useEffect(() => {
    if (!mediaId || !stored) return;
    if (!presignedUrlExpired(stored, Date.now())) return;
    if (hasAttempted(attemptKey(mediaId, stored))) return;
    // The outer ledger, checked here for the reason the block above gives (2026-09-03).
    if (refreshesSpent(mediaId) >= MEDIA_REFRESH_BUDGET) return;
    if (!isOnline()) return;

    let cancelled = false;
    void refreshMediaUrl(mediaId, stored).then((next) => {
      if (!cancelled && next && next !== stored) setFresh(next);
    });
    return () => {
      cancelled = true;
    };
  }, [mediaId, stored]);

  // REACTIVE. The element failed and told us nothing; the module finds out what happened.
  const onError = useCallback(() => {
    if (!media) return;
    void resolveFailedMediaUrl({ id: mediaId, url: fresh ?? stored }).then((next) => {
      if (next) setFresh(next);
    });
    // `fresh` is in the deps so a second failure — of the URL this hook itself installed — is
    // probed against the URL that actually failed rather than against the one that already did.
  }, [media, mediaId, stored, fresh]);

  return { src: fresh ?? stored, onError };
}

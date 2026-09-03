import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import {
  attemptKey,
  hasAttempted,
  isOnline,
  isPresignedUrl,
  MEDIA_REFRESH_BUDGET,
  presignedExpiryAt,
  presignedUrlExpired,
  refreshesSpent,
  refreshVerdict,
  resetMediaRefreshLedger,
  resolveFailedMediaUrl,
  type MediaFailure
} from "@/lib/mediaUrlRefresh";

/**
 * THE SINGLE RETRY BEHIND AN EXPIRED MEDIA URL — AND, ABOVE ALL, THAT IT IS SINGLE.
 *
 * ── WHAT THIS DEFENDS ───────────────────────────────────────────────────────────────────────────
 *
 * `MediaFile.url` is about to stop being permanent. The API grows a flag (`MEDIA_PRESIGNED_READS`)
 * that replaces the stored public CDN link with a 15-minute signature, and after that a human
 * removes the bucket's public-read statement so every URL that ever leaked starts answering 403.
 * Both moves break a browser holding a URL it received earlier — a store rehydrated from IndexedDB,
 * a workshop left open over lunch, a gallery in a form somebody has been typing in for half an hour.
 *
 * `lib/mediaUrlRefresh.ts` answers exactly one way: re-read the owning row (`GET /media/{id}`, which
 * is entitlement-gated) and try the URL it gives back, ONCE.
 *
 * ── THE FOUR WAYS THIS GOES WRONG, WHICH IS WHY EACH HAS A SECTION ──────────────────────────────
 *
 *   1. **IT LOOPS.** An image whose refreshed URL also fails re-arms `onError`, which refreshes,
 *      which re-arms. On a gallery of forty photographs that is a request storm aimed at the API by
 *      a page that looks idle. Section 3 is the ledger, and it is the section to break first if you
 *      are changing this file. IT HAS TWO LAYERS SINCE 2026-09-03 and the second is the load-bearing
 *      one: the (media, url) key alone cannot terminate, because a refresh mints a NEW url every
 *      time and the next failure is therefore a new key. The budget per media id is what stops it.
 *   2. **IT REFRESHES WHAT A REFRESH CANNOT FIX.** A 404 is a deleted object and a 5xx is storage
 *      being unwell; a fresh signature points at the same absence and adds load to the same trouble.
 *   3. **IT FIRES WHILE THE FLAG IS OFF.** This ships weeks before the server flips. A permanent CDN
 *      link has no expiry to read, so nothing may be pre-emptively refreshed and the browser must
 *      behave exactly as it did before this module existed. Section 1 pins that.
 *   4. **IT PRETENDS OFFLINE IS A SMALLER ONLINE.** The web keeps no bytes for remote media (the
 *      draft store clears `blob` the moment the server acknowledges an upload), so an offline reader
 *      whose signatures have expired cannot be rescued at all. The honest behaviour is to do
 *      nothing; a queued refresh would repaint a screen they have already left.
 *
 * ── WHAT IS CALLED AND WHAT IS PINNED BY READING ────────────────────────────────────────────────
 *
 * The decisions are pure and exported, so they are called. `resolveFailedMediaUrl` is driven end to
 * end against a stubbed `fetch`, which is the only way to check that the probe, the verdict and the
 * ledger agree — three correct halves that disagree at the seam is exactly the shape of the bug this
 * file is about. The React hook is pinned by reading its source, the same way
 * `outbox-client-key-unit.spec.ts` pins its mint site: there is no React renderer in
 * devDependencies, so a judgement inside a hook body is only ever exercised by somebody looking at a
 * screen.
 */

const HOOK_SOURCE = readFileSync(
  join(__dirname, "..", "components", "hooks", "useMediaUrl.ts"),
  "utf8"
);
const LIGHTBOX_SOURCE = readFileSync(
  join(__dirname, "..", "components", "media", "MediaLightbox.tsx"),
  "utf8"
);

const MEDIA_ID = "cm-pit-loom-photo";
/** A signed URL as S3 mints one: basic-format date, an expiry in seconds, a signature. */
function signed(stamp: string, expiresIn: number): string {
  return (
    "https://bucket.s3.dualstack.ap-south-1.amazonaws.com/media/u1/pit-loom.jpg" +
    "?X-Amz-Algorithm=AWS4-HMAC-SHA256" +
    `&X-Amz-Date=${stamp}` +
    `&X-Amz-Expires=${expiresIn}` +
    "&X-Amz-SignedHeaders=host" +
    "&X-Amz-Signature=8f2c0b2ab1de"
  );
}

/** 2026-09-03T10:00:00Z, as epoch ms — the moment every signature below is measured against. */
const NOON = Date.UTC(2026, 8, 3, 10, 0, 0);
const PERMANENT = "https://cdn.example.test/media/u1/pit-loom.jpg";

const never = () => false;
const always = () => true;
/** This row has cost nothing yet — the ordinary state of the outer ledger. */
const unspent = () => 0;
/** …and this row has cost everything it may. */
const overspent = () => MEDIA_REFRESH_BUDGET;

test.beforeEach(() => {
  resetMediaRefreshLedger();
});

/* ────────────────────────────────────────────────────────────────────────────
 * 1. A PERMANENT URL IS NEVER TOUCHED — the flag-off world
 * ──────────────────────────────────────────────────────────────────────────── */

test("a stored CDN url is not a signature and never reads as expired", () => {
  expect(isPresignedUrl(PERMANENT)).toBe(false);
  expect(presignedExpiryAt(PERMANENT)).toBeNull();
  // The whole point: with the server flag off, every url in every payload takes this branch, so the
  // pre-emptive refresh in `useMediaUrl` can never fire and the app behaves as it did before.
  expect(presignedUrlExpired(PERMANENT, NOON)).toBe(false);
});

test("an absent url is inert everywhere", () => {
  expect(isPresignedUrl(null)).toBe(false);
  expect(isPresignedUrl(undefined)).toBe(false);
  expect(presignedExpiryAt("")).toBeNull();
  expect(presignedUrlExpired(null, NOON)).toBe(false);
});

/* ────────────────────────────────────────────────────────────────────────────
 * 2. READING A SIGNATURE'S OWN DEATH CERTIFICATE
 * ──────────────────────────────────────────────────────────────────────────── */

test("the basic-format X-Amz-Date is parsed, because Date cannot parse it", () => {
  // `new Date("20260903T095500Z")` is Invalid Date in every browser. A regex that got this wrong
  // would make live URLs look expired and refresh every image on the page exactly once.
  expect(presignedExpiryAt(signed("20260903T095500Z", 900))).toBe(
    Date.UTC(2026, 8, 3, 9, 55, 0) + 900_000
  );
});

test("a signature minted a minute ago is alive; one minted an hour ago is not", () => {
  expect(presignedUrlExpired(signed("20260903T095900Z", 900), NOON)).toBe(false);
  expect(presignedUrlExpired(signed("20260903T090000Z", 900), NOON)).toBe(true);
});

test("a signature about to die inside the round trip is already dead", () => {
  // Expires at 10:00:05, read at 10:00:00 — five seconds is not enough to issue the request, cross a
  // village connection and receive an answer, and a signature that dies mid-transfer yields a
  // TRUNCATED image rather than a clean failure. The slack is what turns that into a refresh.
  expect(presignedUrlExpired(signed("20260903T094500Z", 905), NOON)).toBe(true);
  // Well clear of the slack, and therefore left alone.
  expect(presignedUrlExpired(signed("20260903T094500Z", 1200), NOON)).toBe(false);
});

test("a url that does not say when it dies is not guessed at", () => {
  const noDate =
    "https://bucket.s3.amazonaws.com/k?X-Amz-Signature=abc&X-Amz-Expires=900";
  const noExpiry =
    "https://bucket.s3.amazonaws.com/k?X-Amz-Signature=abc&X-Amz-Date=20260903T095500Z";
  const badStamp = signed("2026-09-03T09:55:00Z", 900);

  // "This URL does not tell me" is a real answer and is better than a fabricated timestamp, which
  // would either refresh everything on the page or nothing at all, depending on which way it erred.
  expect(presignedExpiryAt(noDate)).toBeNull();
  expect(presignedExpiryAt(noExpiry)).toBeNull();
  expect(presignedExpiryAt(badStamp)).toBeNull();
  expect(presignedUrlExpired(badStamp, NOON)).toBe(false);
});

/* ────────────────────────────────────────────────────────────────────────────
 * 3. THE VERDICT — and the loop guard, which is the whole property
 * ──────────────────────────────────────────────────────────────────────────── */

const FORBIDDEN: MediaFailure = { kind: "status", status: 403 };

test("a 403 on a row we can name is refreshed", () => {
  expect(
    refreshVerdict({
      mediaId: MEDIA_ID,
      url: signed("20260903T090000Z", 900),
      failure: FORBIDDEN,
      online: true,
      attempted: never,
      spent: unspent
    })
  ).toBe("refresh");
});

test("THE INNER LOOP GUARD: the same media and the same url are refreshed exactly once", () => {
  const url = signed("20260903T090000Z", 900);
  const input = { mediaId: MEDIA_ID, url, failure: FORBIDDEN, online: true, spent: unspent };

  expect(refreshVerdict({ ...input, attempted: never })).toBe("refresh");
  expect(refreshVerdict({ ...input, attempted: always })).toBe("already-tried");
});

test("the inner ledger keys on the URL, so a REFRESHED url may fail on its own terms", () => {
  // Not a loophole — the second attempt is against a DIFFERENT string, which is a different fact
  // about the world. What this layer forbids is re-asking the same question about the same URL.
  // What it CANNOT do is terminate, which is the next test.
  const first = attemptKey(MEDIA_ID, signed("20260903T090000Z", 900));
  const second = attemptKey(MEDIA_ID, signed("20260903T100000Z", 900));
  expect(first).not.toBe(second);
});

test("THE TERMINATOR: a row out of budget is refused however new the url is", () => {
  /*
    THE DEFECT THIS PINS (2026-09-03). Every refresh mints a fresh signature, so `attempted` is
    false for every subsequent failure of the same file and the inner ledger never says stop. An
    element that cannot render its bytes at all re-arms `onError` on each new url, and the module
    answered "refresh" every time — `GET /media/{id}` for as long as the tab stayed open.
  */
  expect(
    refreshVerdict({
      mediaId: MEDIA_ID,
      // A url nobody has ever asked about: the inner layer has nothing to say.
      url: signed("20260903T101500Z", 900),
      failure: FORBIDDEN,
      online: true,
      attempted: never,
      spent: overspent
    })
  ).toBe("budget-spent");
  // And it is refused before the failure is even classified, so it costs no probe either.
  expect(
    refreshVerdict({
      mediaId: MEDIA_ID,
      url: signed("20260903T101500Z", 900),
      failure: { kind: "unreadable" },
      online: true,
      attempted: never,
      spent: overspent
    })
  ).toBe("budget-spent");
});

test("the budget is a floor, not a fence: the last unit is still spendable", () => {
  // Off-by-one in the permissive direction leaves the loop open; in the strict direction it kills
  // the ordinary repair. `>=` against a count of requests ISSUED is the only reading that does
  // neither, and BUDGET - 1 spent must still refresh.
  expect(
    refreshVerdict({
      mediaId: MEDIA_ID,
      url: signed("20260903T090000Z", 900),
      failure: FORBIDDEN,
      online: true,
      attempted: never,
      spent: () => MEDIA_REFRESH_BUDGET - 1
    })
  ).toBe("refresh");
});

test("offline gives up rather than queueing, and says so", () => {
  expect(
    refreshVerdict({
      mediaId: MEDIA_ID,
      url: signed("20260903T090000Z", 900),
      failure: FORBIDDEN,
      online: false,
      attempted: never,
      spent: unspent
    })
  ).toBe("offline");
});

test("offline is decided BEFORE either ledger, so a failed offline read costs no attempt", () => {
  // Order matters: recording an attempt for a refresh that could never have been issued would spend
  // a retry on a network that was not there, and the reader would then be holding a permanently
  // broken image after they came back online.
  expect(
    refreshVerdict({
      mediaId: MEDIA_ID,
      url: PERMANENT,
      failure: FORBIDDEN,
      online: false,
      attempted: always,
      spent: overspent
    })
  ).toBe("offline");
});

test("a local preview with no persisted id has nothing to re-request", () => {
  expect(
    refreshVerdict({
      mediaId: null,
      url: "blob:x",
      failure: FORBIDDEN,
      online: true,
      attempted: never,
      spent: unspent
    })
  ).toBe("no-record");
});

test("a 404 or a 5xx is left broken and honest", () => {
  for (const status of [404, 410, 500, 503]) {
    expect(
      refreshVerdict({
        mediaId: MEDIA_ID,
        url: PERMANENT,
        failure: { kind: "status", status },
        online: true,
        attempted: never,
        spent: unspent
      }),
      String(status)
    ).toBe("gone");
  }
});

test("a failure we could not read is refreshed once — the CORS case", () => {
  // A bucket that publishes no CORS headers to this origin makes the probe throw, so an expired
  // signature is INVISIBLE. Treating "I could not tell" as "probably fine" would leave every stale
  // URL broken on exactly those deployments; one request is the cost of being wrong, and the two
  // ledgers above have already bounded it.
  expect(
    refreshVerdict({
      mediaId: MEDIA_ID,
      url: PERMANENT,
      failure: { kind: "unreadable" },
      online: true,
      attempted: never,
      spent: unspent
    })
  ).toBe("refresh");
});

test("an unknowable network reads as online, never as offline", () => {
  // The offline branch GIVES UP. A runtime with no `navigator.onLine` — a server render, this Node
  // process — must not therefore disable the whole repair by accident.
  expect(isOnline()).toBe(true);
});

/* ────────────────────────────────────────────────────────────────────────────
 * 4. THE THREE PIECES TOGETHER, AGAINST A STUBBED NETWORK
 * ──────────────────────────────────────────────────────────────────────────── */

type Call = { url: string; range: string | null };

/** Install a fetch that answers 403 for object storage and a fresh row for `GET /api/media/{id}`. */
function stubNetwork(freshUrl: string | null, objectStatus = 403) {
  const calls: Call[] = [];
  const original = globalThis.fetch;
  globalThis.fetch = (async (input: unknown, init?: RequestInit) => {
    const url = String(input);
    const headers = new Headers(init?.headers);
    calls.push({ url, range: headers.get("Range") });
    if (url.includes("/api/media/")) {
      return new Response(JSON.stringify({ id: MEDIA_ID, url: freshUrl }), {
        status: 200,
        headers: { "Content-Type": "application/json" }
      });
    }
    return new Response("", { status: objectStatus });
  }) as typeof globalThis.fetch;
  return { calls, restore: () => { globalThis.fetch = original; } };
}

/**
 * The loop's own conditions: object storage refuses everything and the API mints a NEW signature on
 * every read — which is not a contrivance, it is what a server with `MEDIA_PRESIGNED_READS` on does.
 */
function stubMintingNetwork() {
  let minted = 0;
  const calls: Call[] = [];
  const original = globalThis.fetch;
  globalThis.fetch = (async (input: unknown, init?: RequestInit) => {
    const url = String(input);
    const headers = new Headers(init?.headers);
    calls.push({ url, range: headers.get("Range") });
    if (url.includes("/api/media/")) {
      minted += 1;
      const fresh = signed(`20260903T10${String(minted).padStart(2, "0")}00Z`, 900);
      return new Response(JSON.stringify({ id: MEDIA_ID, url: fresh }), {
        status: 200,
        headers: { "Content-Type": "application/json" }
      });
    }
    return new Response("", { status: 403 });
  }) as typeof globalThis.fetch;
  return { calls, restore: () => { globalThis.fetch = original; } };
}

test("a 403 becomes exactly one row re-read, and the fresh url is handed back", async () => {
  const stale = signed("20260903T090000Z", 900);
  const fresh = signed("20260903T100000Z", 900);
  const net = stubNetwork(fresh);
  try {
    expect(await resolveFailedMediaUrl({ id: MEDIA_ID, url: stale })).toBe(fresh);

    // The probe is a ONE-BYTE ranged GET, not a HEAD: a presigned URL signs its method, so a HEAD
    // against a GET signature is refused and would report every healthy URL as broken.
    expect(net.calls[0].url).toBe(stale);
    expect(net.calls[0].range).toBe("bytes=0-0");
    expect(net.calls[1].url).toContain(`/api/media/${MEDIA_ID}`);
    expect(net.calls).toHaveLength(2);
  } finally {
    net.restore();
  }
});

test("THE SECOND FAILURE OF THE SAME URL COSTS NOTHING", async () => {
  const stale = signed("20260903T090000Z", 900);
  const net = stubNetwork(signed("20260903T100000Z", 900));
  try {
    await resolveFailedMediaUrl({ id: MEDIA_ID, url: stale });
    const afterFirst = net.calls.length;

    // Same image, same url, a second `onError` — the case a re-render or a second renderer of the
    // same file produces. NOTHING must go out, and "nothing" includes the PROBE: the first version
    // of this module probed before consulting the ledger, so every repeat failure cost a ranged GET
    // to object storage for ever, from a page that looks idle. This assertion is what found that.
    expect(await resolveFailedMediaUrl({ id: MEDIA_ID, url: stale })).toBeNull();
    expect(net.calls).toHaveLength(afterFirst);
    expect(hasAttempted(attemptKey(MEDIA_ID, stale))).toBe(true);
  } finally {
    net.restore();
  }
});

test("A GENUINELY UNRENDERABLE FILE STOPS AT THE BUDGET, however many fresh urls it is handed", async () => {
  /*
    THE LOOP, END TO END, AND THE REGRESSION THIS FILE EXISTS TO CATCH TWICE (2026-09-03).

    Every refresh hands back a url nobody has failed on yet, so the inner (media, url) ledger is
    false at every round and answers "refresh" for ever. A corrupt JPEG, a codec this browser will
    not decode, an `<img>` pointed at a PDF: the element re-arms `onError` on each new src and the
    module used to issue `GET /media/{id}` every few hundred milliseconds, from a page that looks
    idle, for as long as the tab stayed open.
  */
  const net = stubMintingNetwork();
  try {
    let url: string | null = signed("20260903T090000Z", 900);
    const handed: string[] = [];
    // Ten `onError`s — a fraction of a second's worth for a re-arming element.
    for (let round = 0; round < 10 && url; round += 1) {
      const next: string | null = await resolveFailedMediaUrl({ id: MEDIA_ID, url });
      if (!next) break;
      handed.push(next);
      url = next;
    }

    expect(handed).toHaveLength(MEDIA_REFRESH_BUDGET);
    // Every url really was different, so the inner layer never fired: the budget is what stopped it.
    expect(new Set(handed).size).toBe(handed.length);
    expect(refreshesSpent(MEDIA_ID)).toBe(MEDIA_REFRESH_BUDGET);
    expect(net.calls.filter((call) => call.url.includes("/api/media/"))).toHaveLength(MEDIA_REFRESH_BUDGET);
    // AND THE PROBES STOP WITH THEM. The budget is read before the failure is classified, so a
    // spent row costs object storage nothing either — the defect the ordering note in
    // `resolveFailedMediaUrl` is about, in its second form.
    const spentUrl = handed[handed.length - 1];
    const before = net.calls.length;
    expect(await resolveFailedMediaUrl({ id: MEDIA_ID, url: spentUrl })).toBeNull();
    expect(net.calls).toHaveLength(before);
  } finally {
    net.restore();
  }
});

test("one file's budget is its own — a gallery is not rationed against itself", async () => {
  // Forty different photographs whose signatures expired together are forty genuine repairs. A
  // budget shared across the page would refresh two of them and leave thirty-eight broken.
  const net = stubMintingNetwork();
  try {
    for (const id of ["photo-a", "photo-b", "photo-c"]) {
      expect(await resolveFailedMediaUrl({ id, url: signed("20260903T090000Z", 900) })).toBeTruthy();
      expect(refreshesSpent(id)).toBe(1);
    }
  } finally {
    net.restore();
  }
});

test("a server that hands back the SAME url is answered with null, not with a re-arm", async () => {
  // The URL is not the problem, so installing it again would arm every renderer for a load that has
  // already failed — and the ledger could not stop the second one, because it keys on the url and
  // the url did not change.
  const stale = signed("20260903T090000Z", 900);
  const net = stubNetwork(stale);
  try {
    expect(await resolveFailedMediaUrl({ id: MEDIA_ID, url: stale })).toBeNull();
  } finally {
    net.restore();
  }
});

test("a row whose url the server now withholds resolves to null, and clears nothing", async () => {
  // `MediaFile.url` absent is the encoder's ENTITLEMENT answer (a grant was revoked between the two
  // reads), not an error. Null means "leave what is on screen alone", which is what every media
  // component in this app already draws for a row with no url.
  const net = stubNetwork(null);
  try {
    expect(await resolveFailedMediaUrl({ id: MEDIA_ID, url: PERMANENT })).toBeNull();
  } finally {
    net.restore();
  }
});

test("a 404 on the object never reaches the API at all", async () => {
  const net = stubNetwork(signed("20260903T100000Z", 900), 404);
  try {
    expect(await resolveFailedMediaUrl({ id: MEDIA_ID, url: PERMANENT })).toBeNull();
    expect(net.calls).toHaveLength(1);
    expect(net.calls[0].url).toBe(PERMANENT);
  } finally {
    net.restore();
  }
});

test("a row with no url is not probed", async () => {
  const net = stubNetwork(signed("20260903T100000Z", 900));
  try {
    expect(await resolveFailedMediaUrl({ id: MEDIA_ID, url: null })).toBeNull();
    expect(net.calls).toHaveLength(0);
  } finally {
    net.restore();
  }
});

/* ────────────────────────────────────────────────────────────────────────────
 * 5. THE HOOK, PINNED BY READING — no React renderer in devDependencies
 * ──────────────────────────────────────────────────────────────────────────── */

test("the hook holds no ref guard, which is the deadlock shape reactStrictMode punishes", () => {
  // An effect that claims a ref on start and releases it only on completion stalls permanently under
  // setup -> cleanup -> setup. The loop guard lives in the module's ledger instead, keyed by data
  // rather than by lifecycle, so it cannot be left held by an attempt that was torn down.
  expect(HOOK_SOURCE).not.toContain("useRef");
  expect(HOOK_SOURCE).toContain("let cancelled = false;");
});

test("the pre-emptive arm checks the budget itself, because it never asks for a verdict", () => {
  // It refreshes BEFORE there is a failure to classify, so it cannot inherit `refreshVerdict`'s
  // guards and has to spell this one. Without it a row could exceed its budget by exactly the number
  // of components that mounted it while its stored signature was already dead.
  expect(HOOK_SOURCE).toContain("refreshesSpent(mediaId) >= MEDIA_REFRESH_BUDGET");
});

test("the hook drops its override when the row's own url changes", () => {
  // A stale override outliving its row is how one photograph ends up drawn under another's caption.
  expect(HOOK_SOURCE).toContain("setFresh(null);");
  expect(HOOK_SOURCE).toContain("}, [stored, mediaId]);");
});

test("the hook never clears a url it could not replace", () => {
  // `src` moves forwards only. Every write is guarded on a truthy, DIFFERENT answer, so a null from
  // the module can never turn a broken-but-present image into an empty frame.
  const writes = HOOK_SOURCE.match(/setFresh\(/g) ?? [];
  expect(writes.length).toBe(3);
  expect(HOOK_SOURCE).toContain("if (!cancelled && next && next !== stored) setFresh(next);");
  expect(HOOK_SOURCE).toContain("if (next) setFresh(next);");
});

test("the lightbox draws its viewer, Save and Open from ONE resolved url", () => {
  // A refreshed signature that reached the <img> but not the download button is the shape of "the
  // picture is there and Save 403s". Neither control may read `item.url` directly any more.
  const viewer = LIGHTBOX_SOURCE.slice(LIGHTBOX_SOURCE.indexOf("export function MediaLightbox"));
  expect(viewer).toContain("saveToDevice(src, item.name)");
  expect(viewer).not.toContain("saveToDevice(item.url");
  expect(viewer).not.toContain("href={item.url}");
});

import { expect, test } from "@playwright/test";

import { downloadMediaUrl } from "@/lib/localMediaDownload";

type FakeAnchor = { href: string; download: string; target: string; rel: string; clicked: boolean; click: () => void; remove: () => void };

async function withFakeBrowser(run: (anchors: FakeAnchor[]) => Promise<void>): Promise<void> {
  const anchors: FakeAnchor[] = [];
  const holder = globalThis as unknown as { document?: unknown; fetch: typeof fetch };
  const previousDocument = holder.document;
  const previousFetch = holder.fetch;
  holder.document = {
    createElement: () => {
      const anchor: FakeAnchor = {
        href: "", download: "", target: "", rel: "", clicked: false,
        click() { this.clicked = true; },
        remove() {}
      };
      anchors.push(anchor);
      return anchor;
    },
    body: { appendChild: () => undefined }
  };
  holder.fetch = (async () => {
    throw new TypeError("Failed to fetch");
  }) as typeof fetch;
  try {
    await run(anchors);
  } finally {
    holder.document = previousDocument;
    holder.fetch = previousFetch;
  }
}

for (const hostile of ["javascript:alert(document.cookie)", " JavaScript:alert(1)", "data:text/html,<script>alert(1)</script>", "vbscript:msgbox(1)"]) {
  test(`a media url of ${JSON.stringify(hostile)} never reaches a clicked anchor`, async () => {
    await withFakeBrowser(async (anchors) => {
      await downloadMediaUrl(hostile, "recording.m4a", "audio/mp4");
      expect(anchors.some((anchor) => anchor.clicked)).toBe(false);
    });
  });
}

test("an https url that cannot be fetched still opens for download", async () => {
  const url = "https://bucket.s3.ap-south-1.amazonaws.com/media/u/k/a.m4a?X-Amz-Signature=abc";
  await withFakeBrowser(async (anchors) => {
    await downloadMediaUrl(url, "recording.m4a", "audio/mp4");
    expect(anchors.map((anchor) => [anchor.href, anchor.clicked])).toEqual([[url, true]]);
  });
});

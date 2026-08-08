import { expect, test, type Page } from "@playwright/test";

import { signIn } from "./support/session";

/**
 * The rich-text editor actually reaches the screen.
 *
 * This spec exists because the whole feature once shipped invisible. Every piece was built and
 * every piece was correct — the portable document model (`lib/richText.ts`), the 2000-line editor
 * (`components/designworkshop/RichTextEditor.tsx`), the branch that mounts it
 * (`FieldInput.tsx`, `case "RICH_TEXT"`), the Kotlin port, and both server renderers — but the
 * server registry declared **zero** fields of type `RICH_TEXT`. So `FieldInput`'s branch was
 * unreachable, every narrative field rendered as a plain textarea, and nothing anywhere failed.
 * A unit test of the editor passes in that world. A test of the registry passes too. Only asking
 * the browser "is there an editor on this page?" catches it, which is what this does.
 *
 * It therefore asserts the JOIN, not the parts:
 *   1. the registry serves at least one RICH_TEXT field for the stage under test, and
 *   2. the page renders a real `contenteditable` with the formatting toolbar for it, and
 *   3. a mark applied in that editor survives a save and a reload.
 *
 * Point 3 is what stops the editor from being re-degraded to a textarea by a future change: a
 * textarea can hold the words, but it cannot hold the bold.
 */

const EMAIL = process.env.E2E_EMAIL ?? "";
const PASSWORD = process.env.E2E_PASSWORD ?? "";
const API = process.env.E2E_API_URL ?? "http://localhost:8000";

test.skip(!EMAIL || !PASSWORD, "Set E2E_EMAIL and E2E_PASSWORD to run the signed-in specs.");

/**
 * Stage 2 is used because every one of its prose fields is NARRATIVE, so it is the densest
 * concentration of rich fields in the registry, and because it is reachable on a brand-new
 * workshop without filling in stage 1 first.
 */
const STAGE_KEY = "INTRODUCTORY_ADMIN_DOCUMENTATION";
const FIELD_KEY = "acknowledgement";

/**
 * How the BROWSER reaches object storage, when that is not simply how the server reaches it.
 *
 * `POST /media/presign` returns a URL naming the endpoint the API is configured with — in a
 * compose stack that is `http://minio:9000`, a hostname that resolves inside the docker network
 * and nowhere else. Every browser upload therefore fails on a developer machine with
 * "network error", which is a REAL failure of the environment and not of the code: the same
 * happens to a stage's photograph fields, and it happens before any code in this repository runs.
 *
 * The signature cannot be worked around by rewriting the URL — SigV4 signs the `Host` header, so a
 * URL pointed at localhost is refused with SignatureDoesNotMatch. What does work is resolving the
 * name differently, which leaves the Host header alone; hence a resolver rule rather than a route.
 *
 * Unset by default, so a correctly-reachable endpoint (CI, or a stack that publishes the port under
 * the name the API hands out) is untouched. Locally:
 *
 *     E2E_OBJECT_STORE_MAP='minio:9000 127.0.0.1:9010'
 */
const OBJECT_STORE_MAP = process.env.E2E_OBJECT_STORE_MAP ?? "";
if (OBJECT_STORE_MAP) {
  test.use({ launchOptions: { args: [`--host-resolver-rules=MAP ${OBJECT_STORE_MAP}`] } });
}

async function apiToken(page: Page): Promise<string> {
  const res = await page.request.post(`${API}/api/auth/login`, {
    data: { email: EMAIL, password: PASSWORD }
  });
  expect(res.ok(), "sign-in for the API fixture").toBeTruthy();
  return (await res.json()).accessToken as string;
}

test("a narrative field renders the rich-text editor, and a mark survives a round trip", async ({
  page
}) => {
  const token = await apiToken(page);
  const auth = { Authorization: `Bearer ${token}` };

  // 1. The registry must actually declare this field rich. If this fails, the editor is missing
  //    for the reason the whole spec was written about, and the browser assertions below would
  //    only tell you "no editor" without telling you why.
  const schema = await (await page.request.get(`${API}/api/design-workshops/schema`, { headers: auth })).json();
  const stage = schema.stages.find((s: { key: string }) => s.key === STAGE_KEY);
  const field = stage?.entities
    ?.flatMap((e: { fields: { key: string; type: string }[] }) => e.fields)
    ?.find((f: { key: string }) => f.key === FIELD_KEY);
  expect(field, `${STAGE_KEY}.${FIELD_KEY} is in the registry`).toBeTruthy();
  expect(
    field.type,
    `${FIELD_KEY} must be RICH_TEXT — if this is LONG_TEXT the editor cannot mount, which is the ` +
      `exact regression this spec exists to catch`
  ).toBe("RICH_TEXT");

  // 2. A workshop to type into. Created through the API so the spec tests the editor rather than
  //    the create form, which has its own specs.
  const created = await page.request.post(`${API}/api/design-workshops`, {
    headers: auth,
    data: { title: `Rich text spec ${Date.now()}` }
  });
  expect(created.ok(), "create a design workshop").toBeTruthy();
  const workshopId = (await created.json()).id as string;

  await signIn(page);
  await page.goto(`/design-workshops/${workshopId}/stages/${STAGE_KEY}`);

  // 3. The editor is on the page — a contenteditable, not a textarea, with its toolbar.
  const editor = page.locator('[contenteditable="true"]').first();
  await expect(editor, "the rich-text editor mounted for a NARRATIVE field").toBeVisible({
    timeout: 30_000
  });

  await editor.click();
  await page.keyboard.type("The cluster is grateful to the master weavers of Barpali.");

  // The toolbar is contextual: it appears for a selection. Select the last word and embolden it.
  await page.keyboard.down("Shift");
  for (let i = 0; i < 8; i += 1) await page.keyboard.press("ArrowLeft");
  await page.keyboard.up("Shift");

  const toolbar = page.getByRole("toolbar", { name: "Text formatting" });
  await expect(toolbar, "the contextual toolbar appears for a selection").toBeVisible();
  await page.keyboard.press("Control+b");

  // 4. Save, reload, and require the MARK back — not merely the text. A textarea would keep every
  //    character here and still fail, which is the point.
  await page.getByRole("button", { name: /^save/i }).first().click();
  await expect(page.getByText(/saved/i).first()).toBeVisible({ timeout: 30_000 });

  const stored = await (
    await page.request.get(`${API}/api/design-workshops/${workshopId}/stages/${STAGE_KEY}`, {
      headers: auth
    })
  ).json();
  const value = JSON.stringify(stored);
  expect(value, "the stored value is a block document, not a flat string").toContain("blocks");
  expect(value, "the bold mark reached the server").toContain("BOLD");

  await page.reload();
  const reloaded = page.locator('[contenteditable="true"]').first();
  await expect(reloaded).toBeVisible({ timeout: 30_000 });
  await expect(
    reloaded.locator("strong, b"),
    "the mark is rendered back into the editor after a reload"
  ).toHaveCount(1);
});

/**
 * A "one per line" field opens as a numbered list, and Enter starts the next item.
 *
 * The registry marks these fields `report_role=BULLETS` and their help text says it out loud —
 * "One deliverable per line", "One objective per line". Before this, they were plain textareas: the
 * help asked for a list, the report printed a list, and the designer got a box with no list in it.
 * Typing "1. " to obtain the behaviour the label already promised is a workaround, not a feature.
 */
test("a one-per-line field opens as a numbered list and Enter starts the next item", async ({
  page
}) => {
  const token = await apiToken(page);
  const auth = { Authorization: `Bearer ${token}` };

  const schema = await (
    await page.request.get(`${API}/api/design-workshops/schema`, { headers: auth })
  ).json();
  const field = schema.stages
    .find((s: { key: string }) => s.key === STAGE_KEY)
    ?.entities?.flatMap((e: { fields: unknown[] }) => e.fields)
    ?.find((f: { key: string }) => f.key === "expectedDeliverables");

  expect(field, "expectedDeliverables is in the registry").toBeTruthy();
  expect(field.reportRole, "it is a BULLETS field").toBe("BULLETS");
  expect(field.type, "and it must be RICH_TEXT for the editor to make a list of it").toBe(
    "RICH_TEXT"
  );

  const created = await page.request.post(`${API}/api/design-workshops`, {
    headers: auth,
    data: { title: `Bullets spec ${Date.now()}` }
  });
  const workshopId = (await created.json()).id as string;

  await signIn(page);
  await page.goto(`/design-workshops/${workshopId}/stages/${STAGE_KEY}`);

  // The editors are in registry order; find the one labelled for this field.
  const group = page.locator("div").filter({ hasText: /Expected deliverables/i }).last();
  const editor = group.locator('[contenteditable="true"]').first();
  await expect(editor).toBeVisible({ timeout: 30_000 });

  await editor.click();
  await page.keyboard.type("Six developed prototypes");
  await page.keyboard.press("Enter");
  await page.keyboard.type("A cost sheet for each prototype");

  // Two list items, without anybody typing "1. ".
  await expect(
    editor.locator("li"),
    "Enter produced a second list item rather than a bare paragraph"
  ).toHaveCount(2);
  await expect(editor.locator("ol")).toHaveCount(1);
});

/**
 * A narrative field offers dictation, and dictating cannot destroy the formatting already there.
 *
 * The button's PRESENCE is environment-dependent and deliberately not asserted unconditionally:
 * `Dictation.tsx` renders nothing when neither the Web Speech API nor the server fallback is
 * available, and headless Chromium has no `SpeechRecognition`. Asserting it were always visible
 * would fail on CI for a reason that has nothing to do with this feature.
 *
 * What IS asserted unconditionally is the thing that was actually wrong. `FieldInput` must not
 * mount its own dictation button on a rich field: its handler commits a bare STRING, and the
 * server reads a bare string in a RICH_TEXT field as unformatted prose, so one dictated sentence
 * would silently flatten every heading, list and bold run already written. The editor's own button
 * inserts into the document model at the caret instead. Two buttons here would mean the
 * destructive path had come back.
 */
test("a narrative field's dictation cannot flatten what is already written", async ({ page }) => {
  const token = await apiToken(page);
  const auth = { Authorization: `Bearer ${token}` };

  const created = await page.request.post(`${API}/api/design-workshops`, {
    headers: auth,
    data: { title: `Dictation spec ${Date.now()}` }
  });
  const workshopId = (await created.json()).id as string;

  await signIn(page);
  await page.goto(`/design-workshops/${workshopId}/stages/${STAGE_KEY}`);

  const editor = page.locator('[contenteditable="true"]').first();
  await expect(editor).toBeVisible({ timeout: 30_000 });

  const dictateButtons = page.getByRole("button", { name: /dictate .* in /i });
  const editors = await page.locator('[contenteditable="true"]').count();
  const buttons = await dictateButtons.count();

  // Never more than one per editor. More would mean FieldInput had re-added the string-committing
  // button beside the editor's own, and the designer would have two controls, one of which
  // destroys their formatting.
  expect(buttons, "at most one dictation control per rich field").toBeLessThanOrEqual(editors);

  // The field is a real document, so a dictation commit routed through the model cannot replace it
  // wholesale the way a string commit into a textarea would.
  await editor.click();
  await page.keyboard.type("Barpali weaves bandha.");
  await page.keyboard.down("Shift");
  for (let i = 0; i < 7; i += 1) await page.keyboard.press("ArrowLeft");
  await page.keyboard.up("Shift");
  await page.keyboard.press("Control+b");

  await expect(editor.locator("strong, b")).toHaveCount(1);
});

/**
 * A table can be inserted, typed into, and survives a save.
 *
 * The table is the one block this editor does NOT edit through the model: a `DocPoint` is
 * `{block, offset}` and cannot address a cell, so cell editing is left to the browser and read
 * back by `readTableElement` on the next input. That inversion is the risk this spec covers — it
 * would be entirely possible for the grid to look right on screen and reach the server as one
 * flattened paragraph, or as nothing at all.
 *
 * So it asserts on the STORED value: a TABLE block, with rows, containing the typed cells.
 */
test("a table can be inserted, typed into, and reaches the server as a table", async ({ page }) => {
  const token = await apiToken(page);
  const auth = { Authorization: `Bearer ${token}` };

  const created = await page.request.post(`${API}/api/design-workshops`, {
    headers: auth,
    data: { title: `Table spec ${Date.now()}` }
  });
  const workshopId = (await created.json()).id as string;

  await signIn(page);
  await page.goto(`/design-workshops/${workshopId}/stages/${STAGE_KEY}`);

  const editor = page.locator('[contenteditable="true"]').first();
  await expect(editor).toBeVisible({ timeout: 30_000 });

  await editor.click();
  await page.keyboard.type("Cost heads for the selected prototypes.");

  // The toolbar is contextual; a selection brings it up.
  await page.keyboard.down("Shift");
  for (let i = 0; i < 5; i += 1) await page.keyboard.press("ArrowLeft");
  await page.keyboard.up("Shift");

  await page.getByRole("button", { name: /insert table/i }).first().click();

  const table = editor.locator("table");
  await expect(table, "a table was inserted into the surface").toHaveCount(1);
  await expect(table.locator("tr")).toHaveCount(3);
  await expect(table.locator("th")).toHaveCount(3);

  // Type into the header and then Tab across — the grid behaviour a person expects.
  await table.locator("th").first().click();
  await page.keyboard.type("Head");
  await page.keyboard.press("Tab");
  await page.keyboard.type("PT-01");
  await page.keyboard.press("Tab");
  await page.keyboard.type("PT-02");

  await page.getByRole("button", { name: /^save/i }).first().click();
  await expect(page.getByText(/saved/i).first()).toBeVisible({ timeout: 30_000 });

  // POLLED, not read once. The "Saved" indicator can appear before the editor's debounced sync
  // has pushed the last cell, so a single read races the thing it is checking — which is exactly
  // how this assertion failed intermittently while the feature underneath it was working.
  const tableBlock = await expect
    .poll(
      async () => {
        const stored = await (
          await page.request.get(`${API}/api/design-workshops/${workshopId}/stages/${STAGE_KEY}`, {
            headers: auth
          })
        ).json();
        const blocks = stored?.singleton?.acknowledgement?.blocks ?? [];
        return blocks.find((b: { kind: string }) => b.kind === "TABLE") ?? null;
      },
      { message: "the stored document contains a TABLE block", timeout: 30_000 }
    )
    .not.toBeNull()
    .then(async () => {
      const stored = await (
        await page.request.get(`${API}/api/design-workshops/${workshopId}/stages/${STAGE_KEY}`, {
          headers: auth
        })
      ).json();
      return (stored?.singleton?.acknowledgement?.blocks ?? []).find(
        (b: { kind: string }) => b.kind === "TABLE"
      );
    });

  const flat = JSON.stringify(tableBlock.rows);
  expect(flat, "the header cells reached the server").toContain("Head");
  expect(flat).toContain("PT-01");
  expect(flat).toContain("PT-02");

  // And the report renders it as a real table rather than losing it.
  const report = await page.request.post(`${API}/api/design-workshops/${workshopId}/report`, {
    headers: auth,
    data: { formats: ["DOCX"], record: false }
  });
  expect(report.status(), "the report generates with a table in the prose").toBe(200);
});

/**
 * The three marks added for reports — superscript, subscript and highlight — apply in the editor,
 * displace each other correctly, and reach the server.
 *
 * They are on the toolbar only because each survives all five renderers: `<w:vertAlign>` and
 * `<w:highlight>` in the .docx, a raised glyph and a filled rectangle in both PDF writers, a styled
 * span in the preview. The backend tests pin those. What only a browser can answer is the other
 * half — that the editor stores them at all, and that it never stores the ambiguous span.
 *
 * A CHARACTER CANNOT BE BOTH RAISED AND LOWERED. `w:vertAlign` takes one value and emitting it
 * twice is schema-invalid, so a document carrying both marks on one span would leave each writer
 * to tie-break — and two writers tie-breaking differently prints "m²" on one surface and "m₂" on
 * the other. The model resolves it, and the editor is arranged never to produce it; this asserts
 * the second, because the first is only ever a backstop.
 */
test("superscript survives the round trip, and it displaces subscript", async ({ page }) => {
  const token = await apiToken(page);
  const auth = { Authorization: `Bearer ${token}` };

  const created = await page.request.post(`${API}/api/design-workshops`, {
    headers: auth,
    data: { title: `Superscript spec ${Date.now()}` }
  });
  const workshopId = (await created.json()).id as string;

  await signIn(page);
  await page.goto(`/design-workshops/${workshopId}/stages/${STAGE_KEY}`);

  const editor = page.locator('[contenteditable="true"]').first();
  await expect(editor).toBeVisible({ timeout: 30_000 });

  await editor.click();
  await page.keyboard.type("The shed measures 4.5 m2");

  // Select the final "2" — the toolbar is contextual and appears for a selection.
  await page.keyboard.down("Shift");
  await page.keyboard.press("ArrowLeft");
  await page.keyboard.up("Shift");

  // Subscript FIRST, then superscript over the same character: the second must displace the first
  // rather than sit beside it.
  await page.getByRole("button", { name: /^subscript$/i }).click();
  await expect(editor.locator("sub")).toHaveCount(1);
  await page.getByRole("button", { name: /^superscript$/i }).click();
  await expect(editor.locator("sup"), "superscript replaced the subscript").toHaveCount(1);
  await expect(editor.locator("sub"), "and nothing is left claiming both").toHaveCount(0);

  // Highlight is not part of that pair and must sit happily alongside — a highlighted superscript
  // is an ordinary thing to want, and both writers carry the two properties on one run.
  await page.getByRole("button", { name: /^highlight$/i }).click();
  await expect(editor.locator("mark"), "the highlight applied").toHaveCount(1);
  await expect(editor.locator("sup"), "and did not displace the superscript").toHaveCount(1);

  /*
    DOES THE SERVER ON THE OTHER END KNOW THIS MARK?

    `rich_text._coerce_marks` drops a mark the build has never heard of, deliberately and in
    silence — that is what lets a phone one release ahead save into a field without losing the
    paragraph. The consequence here is that an API still serving a build from before SUPERSCRIPT
    existed answers every assertion below with "the mark is not stored", which is TRUE and is not a
    regression in anything this spec is about. Asking first turns that into a skip that names the
    cause, instead of a failure that reads as "the editor does not store superscript".
  */
  const schema = await (
    await page.request.get(`${API}/api/design-workshops/schema`, { headers: auth })
  ).json();
  const entityKey = schema.stages
    .find((s: { key: string }) => s.key === STAGE_KEY)
    ?.entities?.find((e: { fields: { key: string }[] }) => e.fields.some((f) => f.key === FIELD_KEY))
    ?.key as string;
  const probe = await page.request.put(
    `${API}/api/design-workshops/${workshopId}/stages/${STAGE_KEY}`,
    {
      headers: auth,
      data: {
        replaceCollections: false,
        entries: [
          {
            entityKey,
            data: {
              [FIELD_KEY]: {
                blocks: [{ kind: "PARAGRAPH", spans: [{ text: "probe", marks: ["SUPERSCRIPT"] }] }]
              }
            }
          }
        ]
      }
    }
  );
  const serverKnowsTheMark = probe.ok() && JSON.stringify(await probe.json()).includes("SUPERSCRIPT");

  await page.getByRole("button", { name: /^save/i }).first().click();
  await expect(page.getByText(/saved/i).first()).toBeVisible({ timeout: 30_000 });

  test.skip(
    !serverKnowsTheMark,
    "the API is serving a build that predates SUPERSCRIPT, so it drops the mark on the way in — " +
      "the editor half of this test has already run and passed"
  );

  await expect
    .poll(
      async () => {
        const stored = await (
          await page.request.get(`${API}/api/design-workshops/${workshopId}/stages/${STAGE_KEY}`, {
            headers: auth
          })
        ).json();
        return JSON.stringify(stored?.singleton?.acknowledgement ?? {});
      },
      { message: "the stored document carries SUPERSCRIPT and not SUBSCRIPT", timeout: 30_000 }
    )
    .toContain("SUPERSCRIPT");

  const stored = await (
    await page.request.get(`${API}/api/design-workshops/${workshopId}/stages/${STAGE_KEY}`, {
      headers: auth
    })
  ).json();
  expect(
    JSON.stringify(stored?.singleton?.acknowledgement ?? {}),
    "the displaced mark was never stored"
  ).not.toContain("SUBSCRIPT");
});

/**
 * A photograph can be placed INSIDE the prose, captioned, and reaches the server as an IMAGE block.
 *
 * The whole pipeline for this existed before the control did — `BlockKind.IMAGE` in `rich_text.py`
 * and `RichText.kt`, the mapping onto `report_model.ImageBlock`, the media ids collected out of
 * RICH_TEXT by `design_workshops._media_ids` so the resolver loads them — and none of it was
 * reachable, because the editor had no button. So this spec is the join, exactly as the first one
 * in this file is: the button uploads through the app's own media pipeline, the block that
 * references it is stored, and the caption typed under the picture is stored with it.
 *
 * It asserts on the STORED value rather than on the screen, because the failure this guards
 * against is invisible on screen: a figure that renders perfectly in the browser and reaches the
 * server as a bare paragraph — or as an IMAGE block whose `media` is empty, which both parsers
 * drop — looks identical to a working one until somebody opens the .docx.
 */
test("a photograph can be placed inside the prose and reaches the server as an IMAGE block", async ({
  page
}) => {
  const token = await apiToken(page);
  const auth = { Authorization: `Bearer ${token}` };

  const created = await page.request.post(`${API}/api/design-workshops`, {
    headers: auth,
    data: { title: `Inline image spec ${Date.now()}` }
  });
  const workshopId = (await created.json()).id as string;

  await signIn(page);
  await page.goto(`/design-workshops/${workshopId}/stages/${STAGE_KEY}`);

  const editor = page.locator('[contenteditable="true"]').first();
  await expect(editor).toBeVisible({ timeout: 30_000 });

  await editor.click();
  await page.keyboard.type("The puckering at the seam, below.");

  // The toolbar is contextual: it appears for a selection.
  await page.keyboard.down("Shift");
  for (let i = 0; i < 6; i += 1) await page.keyboard.press("ArrowLeft");
  await page.keyboard.up("Shift");

  const place = page.getByRole("button", { name: /place a photograph/i });
  await expect(place, "the picture control is on the contextual toolbar").toBeVisible();

  /*
    A REAL FILE THROUGH THE REAL UPLOAD PATH. The input is set directly rather than by clicking the
    button, because clicking opens the operating system's file chooser, which Playwright cannot
    drive. What is being tested is everything after the file is chosen — the upload, the media row,
    the block — and that is unchanged either way.

    `data-rte-image-input` and NOT `input[type=file]`: this stage carries a file input for every
    media field it declares, and the first one in the document is one of those. Setting a file on
    it uploads happily and attaches the photograph to the wrong field, so the spec would fail with
    "no figure" while reporting nothing about why.

    The bytes are a 1×1 PNG. Small enough to upload over anything, and a genuine PNG rather than a
    renamed text file, because the media pipeline reads the header.
  */
  const png = Buffer.from(
    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==",
    "base64"
  );
  await page.locator("input[data-rte-image-input]").first().setInputFiles({
    name: "loom-detail.png",
    mimeType: "image/png",
    buffer: png
  });

  const figure = editor.locator("figure");
  await expect(figure, "the figure was placed in the prose").toHaveCount(1, { timeout: 60_000 });
  await expect(figure.locator("img")).toHaveCount(1);

  // The caption is the block's own spans, so it is typed like any other prose — the caret is left
  // in it by the insert.
  await page.keyboard.type("Detail of the seam on prototype PT-01.");
  await expect(figure.locator("figcaption")).toContainText("prototype PT-01");

  await page.getByRole("button", { name: /^save/i }).first().click();
  await expect(page.getByText(/saved/i).first()).toBeVisible({ timeout: 30_000 });

  // POLLED, for the same reason the table spec polls: the "Saved" indicator can appear before the
  // editor's debounced sync has pushed the last characters of the caption.
  await expect
    .poll(
      async () => {
        const stored = await (
          await page.request.get(`${API}/api/design-workshops/${workshopId}/stages/${STAGE_KEY}`, {
            headers: auth
          })
        ).json();
        const blocks = stored?.singleton?.acknowledgement?.blocks ?? [];
        const image = blocks.find((b: { kind: string }) => b.kind === "IMAGE");
        // The media id is the whole point: an IMAGE block without one is dropped by `from_json`
        // and by `fromStored`, so a "present" block that fails this is a photograph that has
        // already been lost.
        return image?.media ? JSON.stringify(image) : null;
      },
      { message: "the stored document contains an IMAGE block carrying a media id", timeout: 30_000 }
    )
    .not.toBeNull();

  const stored = await (
    await page.request.get(`${API}/api/design-workshops/${workshopId}/stages/${STAGE_KEY}`, {
      headers: auth
    })
  ).json();
  const image = (stored?.singleton?.acknowledgement?.blocks ?? []).find(
    (b: { kind: string }) => b.kind === "IMAGE"
  );
  expect(JSON.stringify(image.spans), "the caption reached the server").toContain("prototype PT-01");

  // And the report generates with the photograph embedded in the prose rather than failing on it.
  const report = await page.request.post(`${API}/api/design-workshops/${workshopId}/report`, {
    headers: auth,
    data: { formats: ["DOCX"], record: false }
  });
  expect(report.status(), "the report generates with a picture in the prose").toBe(200);
});

/**
 * A table can be reshaped — rows and columns added and removed at the caret.
 *
 * Insert-only was the first version of this feature and it was not usable: a designer who needed
 * a fourth row had no way to get one, and a 3×3 that did not fit their data was a dead end they
 * could only escape by deleting the whole table and starting again.
 *
 * The controls act at the CARET's row and column rather than at the end of the table, so this
 * puts the caret in the middle and checks the change landed there.
 */
test("a table's rows and columns can be added and removed at the caret", async ({ page }) => {
  const token = await apiToken(page);
  const auth = { Authorization: `Bearer ${token}` };

  const created = await page.request.post(`${API}/api/design-workshops`, {
    headers: auth,
    data: { title: `Table shape spec ${Date.now()}` }
  });
  const workshopId = (await created.json()).id as string;

  await signIn(page);
  await page.goto(`/design-workshops/${workshopId}/stages/${STAGE_KEY}`);

  const editor = page.locator('[contenteditable="true"]').first();
  await expect(editor).toBeVisible({ timeout: 30_000 });
  await editor.click();
  await page.keyboard.type("Costing.");
  await page.keyboard.down("Shift");
  await page.keyboard.press("ArrowLeft");
  await page.keyboard.up("Shift");
  await page.getByRole("button", { name: /insert table/i }).first().click();

  const table = editor.locator("table");
  await expect(table.locator("tr")).toHaveCount(3);
  await expect(table.locator("tr").first().locator("th")).toHaveCount(3);

  // The structure controls appear only when the caret is inside the table.
  await table.locator("td").first().click();
  const addRow = page.getByRole("button", { name: /insert row below/i });
  await expect(addRow, "the row controls appear when the caret is in a cell").toBeVisible();

  await addRow.click();
  await expect(table.locator("tr"), "a row was added").toHaveCount(4);

  await table.locator("td").first().click();
  await page.getByRole("button", { name: /insert column right/i }).click();
  await expect(table.locator("tr").first().locator("th"), "a column was added").toHaveCount(4);

  await table.locator("td").first().click();
  await page.getByRole("button", { name: /delete this row/i }).click();
  await expect(table.locator("tr"), "a row was removed").toHaveCount(3);

  await table.locator("td").first().click();
  await page.getByRole("button", { name: /delete this column/i }).click();
  await expect(table.locator("tr").first().locator("th"), "a column was removed").toHaveCount(3);

  // The header row is protected: a table cannot be reduced past header + one body row, because
  // the header is what becomes the column titles in the report.
  await table.locator("td").first().click();
  await page.getByRole("button", { name: /delete this row/i }).click();
  await expect(table.locator("tr"), "the header and one body row survive").toHaveCount(2);
  await table.locator("td").first().click();
  await page.getByRole("button", { name: /delete this row/i }).click();
  await expect(table.locator("tr"), "and cannot be reduced further").toHaveCount(2);
});

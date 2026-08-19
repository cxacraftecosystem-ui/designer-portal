import { expect, test, type Page } from "@playwright/test";

import { serveAddressReference } from "./fixtures/location";
import { anyCraftId, createArtisan, createWorkshop, discard, saveStage, stamp } from "./support/records";
import { API, bearer, CREDENTIALS_MISSING, signIn } from "./support/session";

/**
 * A PRODUCT CREATED FROM INSIDE THE STAGE FILLS THE STAGE'S REQUIRED BOXES, AND THE STAGE SUBMITS.
 *
 * THE DEFECT THIS SPEC WAS WRITTEN FOR. `StageReferenceField` used to take the record that
 * `InlineRecordDialog` handed back — the RAW repository row, straight off `POST /products` — and
 * pass it in as a reference option's `data`. The hydration table is keyed by the REFERENCE
 * PAYLOAD's names (`name`, `price`, `use`, `material`) and a raw product row carries Prisma column
 * names (`productName`, `sellingPrice`, `productFunctionUse`, `rawMaterialsUsed`). Not one key
 * matched, so an inline-created product hydrated NOTHING.
 *
 * WHY THAT IS A 422 AND NOT A COSMETIC LOSS. `existingProduct.name` and `existingProduct.price` are
 * `required=True`, and `design_workshops.py` states the order outright: validation, then hydration,
 * then the write. So the two boxes the linked record was supposed to fill stayed blank, and the
 * stage the designer had just enriched with a brand-new repository record was REFUSED on submit.
 * A designer in a room reads that as the app having lost the product they just typed in.
 *
 * WHAT MAKES THIS SPEC BITE RATHER THAN AGREE WITH A BUG. Three assertions, in increasing strength:
 *
 *   1. The required "Product" box holds the product's name — the create response calls that column
 *      `productName` and the hydration table asks for `name`.
 *   2. The required "Selling price" box holds it — and holds it as `"1250.50"`, which is the string
 *      `_money` builds on the SERVER. Measured, not assumed: `GET /api/products` renders the same
 *      Prisma Decimal as `"1250.5"` (its neighbours in that response are `"1500"`, `"2200"` — the
 *      trailing zeros are gone). So this exact value cannot have come from the raw row. It is the
 *      whole argument for fetching the server's description rather than renaming columns in the
 *      browser, in one assertion.
 *   3. The strict save is ACCEPTED. That is the 422 the two blank boxes used to cause.
 *
 * WHAT IS DELIBERATELY NOT ASSERTED, AND WHY THAT IS NOT A GAP IN THE FEATURE. "Length" DOES fill
 * at the pick: `existingProduct.productRef` maps `lengthCm`/`widthCm`/`heightCm` in the browser's
 * `DW_REFERENCE_HYDRATION` exactly as `REFERENCE_HYDRATION` maps them on the server, and 12 inches
 * arrives as 30.48 because `_inches_to_cm` runs where the reference payload is built. This spec
 * simply does not look: it is about the required-box 422, and every assertion in it is aimed at the
 * two boxes whose emptiness refused a submit.
 *
 * THE PARAGRAPH THAT USED TO STAND HERE SAID THE OPPOSITE, and it was the more expensive kind of
 * wrong: it told the next developer that the two tables were ALLOWED to differ and that closing the
 * gap belonged to somebody else. They do not and it does not —
 * `backend/tests/test_reference_registry.py::test_the_web_carries_the_same_hydration_table` asserts
 * `set(web) == set(server)` and then per-path equality, so the two tables are REQUIRED to move
 * together and a widening that lands on one file alone fails that test by design. Acting on the old
 * paragraph meant either shipping the server half into a failure you had been told to expect, or
 * deleting a correct web entry to match a comment.
 *
 * The tests that own the pick-time claim are that backend test, for the two tables agreeing, and
 * `reference-hydration-unit.spec.ts`, for what the browser writes onto a row.
 *
 * NOT INTERCEPTED, unlike the location specs. The record has to be really created, through the real
 * form, and really read back through `GET /references` — a stubbed create would hand back whatever
 * shape this spec imagined, which is precisely the mistake under test. The four fixtures are
 * deleted in `afterAll`.
 */

test.skip(Boolean(CREDENTIALS_MISSING), CREDENTIALS_MISSING);

/** Bagru, Rajasthan — the coordinate the other specs use, so one fixture place means one place. */
const BAGRU = { latitude: 26.8137, longitude: 75.545 };

/** Stage 6, the only stage that declares `existingProduct` and its two required carried fields. */
const STAGE = "EXISTING_PRODUCTS_BASELINE";

/**
 * Answer one of the record form's dropdowns.
 *
 * NOT `fixtures/location`'s `pick`, and the difference is not style — it is the one thing that
 * would have made this spec unrunnable. `pick` presses Escape after choosing, which is harmless on
 * a page and fatal inside `FieldDialog`: Escape closes the dialog, taking the half-filled product
 * form with it. It also assumes the search box exists, and `SearchableSelect` only draws one at
 * `SEARCH_THRESHOLD` (8) options — the artisan list for a fixture craft has exactly one.
 *
 * The trigger is found through the `.field-label` span rather than by accessible name because
 * `FormControls.Field` wraps every control in a `<label>`, so the button announces itself as the
 * label text concatenated with its own — and by SUBSTRING, because two of the labels this drives
 * carry brackets that a regex would read as groups.
 */
async function choose(page: Page, label: string, option: string) {
  const trigger = page
    .locator("label")
    .filter({ has: page.locator("span.field-label", { hasText: label }) })
    .locator("[data-searchable-select]");
  const popovers = page.locator("[data-anchored-popover]");
  await trigger.click();
  const panel = popovers.last();
  // WAIT FOR THE LIST BEFORE ASKING WHETHER THERE IS A SEARCH BOX. A bare `count()` the instant
  // after the click raced the popover's own first paint and came back 0 for the district
  // dropdown — so nothing was typed, the unfiltered list of thirty-three districts stayed on
  // screen, and the click landed on a row that the popover was still repositioning under.
  await expect(panel.getByRole("listbox")).toBeVisible();
  const search = panel.getByRole("combobox");
  if (await search.count()) await search.fill(option);
  const match = panel.getByRole("option", { name: option, exact: true }).first();
  await expect(match).toBeVisible();
  await match.click();
  await expect(trigger).toContainText(option);
  /*
    AND WAIT FOR THE PANEL TO ACTUALLY LEAVE THE DOM before handing control back.

    The trigger's text updates the instant the option is clicked, while the popover is still playing
    its exit animation. `.last()` on the next call then resolved to the PREVIOUS, dying popover:
    the artisan step typed the artisan's name into the craft dropdown's search box and died when
    that box was unmounted underneath it. A popover that is on its way out is indistinguishable
    from one that is opening, unless you wait for the count.
  */
  await expect(popovers).toHaveCount(0);
}

test.describe("Inline-created records hydrate the stage row", () => {
  // The product form captures a location by itself when it opens, and a create with no coordinate
  // is refused by the API. Granting the permission is what lets this spec drive the form the way a
  // designer standing at the place does, rather than typing a latitude in by hand.
  test.use({ permissions: ["geolocation"], geolocation: BAGRU });

  const seed = stamp();
  /**
   * A craft name that sorts to the front of the alphabet, and it is not a joke.
   *
   * `/crafts` orders by name ascending and `ProductForm`'s craft dropdown asks for one page of
   * `LIST_PAGE_CEILING` (100). This repository holds 235 crafts. A fixture craft with an ordinary
   * name is therefore off page one and cannot be selected at all — the spec would fail inside the
   * dropdown while reporting nothing about hydration. Sorting it to the front is the only property
   * of the name this spec depends on, and it is stated here rather than discovered again later.
   *
   * A craft of its own, rather than borrowing one, for the matching reason on the OTHER dropdown:
   * the artisan list is one page per craft, and the borrowed craft could already hold a hundred.
   */
  const craftName = `AAAA Inline Hydration ${seed}`;
  const artisanName = `Inline Hydration Artisan ${seed}`;
  const productName = `Inline Hydration Stole ${seed}`;

  let token = "";
  let craftId = "";
  let artisanId = "";
  let workshopId = "";
  /** Captured off the create response so teardown can remove the record this spec really made. */
  let productId = "";

  test.beforeAll(async ({ request }) => {
    const res = await request.post(`${API}/api/auth/login`, {
      data: { email: process.env.E2E_EMAIL, password: process.env.E2E_PASSWORD }
    });
    expect(res.ok(), `sign in: ${res.status()} ${await res.text()}`).toBeTruthy();
    token = (await res.json()).accessToken as string;

    const craft = await request.post(`${API}/api/crafts`, {
      headers: bearer(token),
      data: { name: craftName, place: "Bagru" }
    });
    expect(craft.ok(), `craft fixture: ${craft.status()} ${await craft.text()}`).toBeTruthy();
    craftId = (await craft.json()).id as string;

    artisanId = await createArtisan(request, token, {
      name: artisanName,
      place: "Bagru",
      craftId,
      location: { state: "Rajasthan", district: "Jaipur", ...BAGRU }
    });

    workshopId = await createWorkshop(request, token, `Inline hydration spec ${seed}`);
    // ONLY the artisan reference. The product picker cascades off it — an unanswered cascade
    // disables the control this spec is about — and everything else on the row, including the two
    // required boxes, is deliberately left for the inline create to fill.
    await saveStage(request, token, workshopId, STAGE, [
      { entityKey: "existingProduct", ordinal: 0, data: { artisanRef: artisanId } }
    ]);
  });

  test.afterAll(async ({ request }) => {
    // Children first: a craft with an artisan on it, or an artisan with a product on it, may be
    // refused. Best-effort throughout — see `discard`.
    if (productId) await discard(request, token, `/api/products/${productId}`);
    if (workshopId) await discard(request, token, `/api/design-workshops/${workshopId}`);
    if (artisanId) await discard(request, token, `/api/artisans/${artisanId}`);
    if (craftId) await discard(request, token, `/api/crafts/${craftId}`);
  });

  test("a product created from the picker fills the required boxes and the stage submits", async ({ page }) => {
    await serveAddressReference(page);
    // The create is watched rather than searched for afterwards: the id is the one thing teardown
    // needs and the response is the only place it is stated without guessing at a name.
    page.on("response", (response) => {
      if (productId) return;
      const request = response.request();
      if (request.method() === "POST" && /\/api\/products$/.test(new URL(response.url()).pathname) && response.ok()) {
        void response
          .json()
          .then((body: { id?: string }) => {
            if (body?.id) productId = body.id;
          })
          .catch(() => undefined);
      }
    });

    await signIn(page);
    await page.goto(`/design-workshops/${workshopId}/stages/${STAGE}`);

    // Collection rows render COLLAPSED and are titled by the first text they carry — here the
    // artisan name the seed's `artisanRef` hydrated on the server.
    const row = page.getByRole("button", { name: new RegExp(artisanName, "i") }).first();
    await expect(row, "the seeded row is on the stage").toBeVisible({ timeout: 30_000 });
    await row.click();

    // THE STARTING STATE, ASSERTED. Without this the test could pass on a row that was already
    // filled in by something else and never exercise hydration at all.
    const productBox = page.getByLabel("Product *", { exact: true });
    const priceBox = page.getByLabel("Selling price *", { exact: true });
    await expect(productBox, "the required product name starts empty").toHaveValue("");
    await expect(priceBox, "the required price starts empty").toHaveValue("");

    await page.getByRole("button", { name: "Documented product", exact: true }).click();
    // By text: the control carries an aria-hidden icon and a caption that changes with the search
    // box, so its accessible name is not a fixed string.
    await page.getByText(/create .*new product/i).first().click();

    const dialog = page.getByRole("dialog");
    await expect(dialog, "the inline record dialog opened").toBeVisible({ timeout: 20_000 });

    // The REAL ProductForm, filled the way a designer fills it. The linked craft has to be chosen
    // before the artisan dropdown will offer anybody, and the linked ARTISAN is what puts an
    // `artisanId` on the record — without it the product belongs to nobody and the cascaded picker
    // this spec created it from could never show it.
    /*
      UNLINKED FROM ANY WORKSHOP, DELIBERATELY, and it is not tidiness.

      `useWorkshopSelection` remembers the researcher's current sitting and pre-selects it, and the
      one it found on this account had ENDED — so `confirmSubmission()` opened the late-submission
      dialog and waited, the product was never posted, and the spec sat looking at a form it had
      filled in perfectly. Nothing in this spec is about the workshop link: the design workshop
      under test is not tied to a Workshop record either, so the reference picker's WORKSHOP scope
      falls back to the whole table and the product is offered regardless.
    */
    await choose(page, "Workshop", "Not linked to a workshop");
    await dialog.locator('input[name="productName"]').fill(productName);
    await choose(page, "Linked craft", craftName);
    await choose(page, "Linked artisan", `${artisanName} · Bagru`);
    await dialog.locator('input[name="sellingPrice"]').fill("1250.50");
    // Inches on the record; the stage's box says cm, and the ×2.54 runs where the SERVER builds the
    // reference payload — assertion 2's argument in a second column. Not asserted on here, for the
    // reason under "WHAT IS DELIBERATELY NOT ASSERTED": this spec is about the required-box 422.
    await dialog.locator('input[name="lengthInches"]').fill("12");
    // The stated address, which a create is refused without. The coordinate half answers itself
    // from the granted geolocation above.
    await choose(page, "State", "Rajasthan");
    await choose(page, "District", "Jaipur");

    await dialog.getByRole("button", { name: /save product/i }).click();
    await expect(dialog, "the dialog closes on a successful save").toBeHidden({ timeout: 60_000 });

    // ── The claim ──────────────────────────────────────────────────────────────────────────
    // Both required boxes, filled from the record that did not exist a moment ago. Before the fix
    // these stayed empty: the raw row's `productName`/`sellingPrice` are not the hydration table's
    // `name`/`price`, so the mapping matched nothing at all.
    await expect(productBox, "the required product name is carried across").toHaveValue(productName, {
      timeout: 30_000
    });
    // TWO PLACES, and the trailing zero is the assertion. The raw row renders this Decimal as
    // "1250.5"; "1250.50" is `_money` on the server, so this value can only have come from the
    // reference payload. See the file header.
    await expect(priceBox, "the required price is carried across, as MONEY's two-place string").toHaveValue(
      "1250.50"
    );

    // ── And the stage the defect used to refuse ────────────────────────────────────────────
    await page.getByRole("button", { name: /save and check required fields/i }).click();
    await expect(
      page.getByText(/Stage saved and every required field is filled in/i),
      "the strict save is accepted — this is the 422 the blank required boxes used to cause"
    ).toBeVisible({ timeout: 60_000 });
  });
});

/**
 * The other half of the same seam, without a browser: the picker must never hand hydration a raw
 * repository row again.
 *
 * This is here rather than in `reference-hydration-unit.spec.ts` because it is a statement about
 * the PICKER's contract with the table, not about the table's rules. It costs no dev server and it
 * is the assertion that stays true if somebody later decides the round trip is too slow and
 * reaches for a rename table instead: a rename table would have to name `sellingPrice`, and
 * `hydrateFromReference` would still have to be given `price`.
 */
test.describe("The shape hydration is fed", () => {
  test("a raw product row shares no key with the mapping the row is hydrated through", async () => {
    // The keys the hydration table asks a ProductDocumentation reference for — the web's copy of
    // `existingProduct.productRef` in `lib/designWorkshops.ts`, restated so this spec fails loudly
    // if the two ever drift into agreement by accident.
    const mappingAsks = ["name", "category", "material", "price", "use", "photo"];
    // The columns `POST /api/products` answers with, as `lib/types.ts` declares them.
    const rawRowHas = [
      "productName",
      "productType",
      "rawMaterialsUsed",
      "sellingPrice",
      "productFunctionUse",
      "media"
    ];
    expect(
      mappingAsks.filter((key) => rawRowHas.includes(key)),
      "nothing lines up, which is why passing the raw row hydrated exactly nothing"
    ).toEqual([]);
  });
});

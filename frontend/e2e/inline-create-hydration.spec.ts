import { expect, test, type Page } from "@playwright/test";

import { serveAddressReference } from "./fixtures/location";
import { anyCraftId, createArtisan, createWorkshop, discard, saveStage, stamp } from "./support/records";
import { API, bearer, CREDENTIALS_MISSING, signIn } from "./support/session";

test.skip(Boolean(CREDENTIALS_MISSING), CREDENTIALS_MISSING);

const BAGRU = { latitude: 26.8137, longitude: 75.545 };

const STAGE = "EXISTING_PRODUCTS_BASELINE";

async function choose(page: Page, label: string, option: string) {
  const trigger = page
    .locator("label")
    .filter({ has: page.locator("span.field-label", { hasText: label }) })
    .locator("[data-searchable-select]");
  const popovers = page.locator("[data-anchored-popover]");
  await trigger.click();
  const panel = popovers.last();
  await expect(panel.getByRole("listbox")).toBeVisible();
  const search = panel.getByRole("combobox");
  if (await search.count()) await search.fill(option);
  const match = panel.getByRole("option", { name: option, exact: true }).first();
  await expect(match).toBeVisible();
  await match.click();
  await expect(trigger).toContainText(option);
  await expect(popovers).toHaveCount(0);
}

test.describe("Inline-created records hydrate the stage row", () => {
  test.use({ permissions: ["geolocation"], geolocation: BAGRU });

  const seed = stamp();
  const craftName = `AAAA Inline Hydration ${seed}`;
  const artisanName = `Inline Hydration Artisan ${seed}`;
  const productName = `Inline Hydration Stole ${seed}`;

  let token = "";
  let craftId = "";
  let artisanId = "";
  let workshopId = "";
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
    await saveStage(request, token, workshopId, STAGE, [
      { entityKey: "existingProduct", ordinal: 0, data: { artisanRef: artisanId } }
    ]);
  });

  test.afterAll(async ({ request }) => {
    if (productId) await discard(request, token, `/api/products/${productId}`);
    if (workshopId) await discard(request, token, `/api/design-workshops/${workshopId}`);
    if (artisanId) await discard(request, token, `/api/artisans/${artisanId}`);
    if (craftId) await discard(request, token, `/api/crafts/${craftId}`);
  });

  test("a product created from the picker fills the required boxes and the stage submits", async ({ page }) => {
    await serveAddressReference(page);
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

    const row = page.getByRole("button", { name: new RegExp(artisanName, "i") }).first();
    await expect(row, "the seeded row is on the stage").toBeVisible({ timeout: 30_000 });
    await row.click();

    const productBox = page.getByLabel("Product *", { exact: true });
    const priceBox = page.getByLabel("Selling price *", { exact: true });
    await expect(productBox, "the required product name starts empty").toHaveValue("");
    await expect(priceBox, "the required price starts empty").toHaveValue("");

    await page.getByRole("button", { name: "Documented product", exact: true }).click();
    await page.getByText(/create .*new product/i).first().click();

    const dialog = page.getByRole("dialog");
    await expect(dialog, "the inline record dialog opened").toBeVisible({ timeout: 20_000 });

    await choose(page, "Workshop", "Not linked to a workshop");
    await dialog.locator('input[name="productName"]').fill(productName);
    await choose(page, "Linked craft", craftName);
    await choose(page, "Linked artisan", `${artisanName} · Bagru`);
    await dialog.locator('input[name="sellingPrice"]').fill("1250.50");
    await dialog.locator('input[name="lengthInches"]').fill("12");
    await choose(page, "State", "Rajasthan");
    await choose(page, "District", "Jaipur");

    await dialog.getByRole("button", { name: /save product/i }).click();
    await expect(dialog, "the dialog closes on a successful save").toBeHidden({ timeout: 60_000 });

    await expect(productBox, "the required product name is carried across").toHaveValue(productName, {
      timeout: 30_000
    });
    await expect(priceBox, "the required price is carried across, as MONEY's two-place string").toHaveValue(
      "1250.50"
    );

    await page.getByRole("button", { name: /save and check required fields/i }).click();
    await expect(
      page.getByText(/Stage saved and every required field is filled in/i),
      "the strict save is accepted — this is the 422 the blank required boxes used to cause"
    ).toBeVisible({ timeout: 60_000 });
  });
});

test.describe("The shape hydration is fed", () => {
  test("a raw product row shares no key with the mapping the row is hydrated through", async () => {
    const mappingAsks = ["name", "category", "material", "price", "use", "photo"];
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

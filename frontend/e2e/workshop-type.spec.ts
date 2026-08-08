import { expect, test, type Page } from "@playwright/test";

import { signIn } from "./support/session";

/**
 * A workshop declares its KIND, and only design-prototype ones seed a 22-stage record.
 *
 * A Design & Prototype Development Workshop and an ordinary documentation visit were both
 * `Workshop` rows with nothing to tell them apart. So a designer starting a 22-stage record had to
 * find the right one in a list holding every craft-documentation visit ever recorded — and the
 * cover details they then retyped by hand (craft, cluster, state, district, dates) were already on
 * that row. Retyping is how the two come to disagree, and the report's cover page is built from
 * the retyped copy.
 */

const EMAIL = process.env.E2E_EMAIL ?? "";
const PASSWORD = process.env.E2E_PASSWORD ?? "";
const API = process.env.E2E_API_URL ?? "http://localhost:8000";

test.skip(!EMAIL || !PASSWORD, "Set E2E_EMAIL and E2E_PASSWORD to run the signed-in specs.");

test("the kind is stored, and only design-prototype workshops are offered as a source", async ({
  page
}) => {
  const token = (
    await (await page.request.post(`${API}/api/auth/login`, { data: { email: EMAIL, password: PASSWORD } })).json()
  ).accessToken as string;
  const auth = { Authorization: `Bearer ${token}` };
  const stamp = Date.now();

  const design = await page.request.post(`${API}/api/workshops`, {
    headers: auth,
    data: {
      title: `DPDW spec ${stamp}`,
      place: "Barpali, Bargarh, Odisha",
      workshopType: "DESIGN_PROTOTYPE",
      startDate: "2026-01-12T00:00:00Z",
      endDate: "2026-02-10T00:00:00Z",
      location: { state: "Odisha", district: "Bargarh", latitude: 21.1918, longitude: 83.5906 }
    }
  });
  expect(design.status(), "create a design-prototype workshop").toBe(201);
  expect((await design.json()).workshopType).toBe("DESIGN_PROTOTYPE");

  const other = await page.request.post(`${API}/api/workshops`, {
    headers: auth,
    data: {
      title: `Ordinary spec ${stamp}`,
      place: "Sambalpur",
      startDate: "2026-03-01T00:00:00Z",
      location: { state: "Odisha", district: "Sambalpur", latitude: 21.46, longitude: 83.98 }
    }
  });
  expect((await other.json()).workshopType, "the default is OTHER, as every old row was").toBe("OTHER");

  // The filter the picker uses returns the one and not the other.
  const filtered = await (
    await page.request.get(`${API}/api/workshops?workshopType=DESIGN_PROTOTYPE&pageSize=100`, { headers: auth })
  ).json();
  // Case-folded: the API title-cases a workshop title on the way in ("spec" -> "Spec"), which is
  // existing normalisation and not this feature's business.
  const titles = (filtered.items ?? []).map((w: { title: string }) => String(w.title).toLowerCase());
  expect(titles).toContain(`dpdw spec ${stamp}`);
  expect(titles, "an ordinary workshop must not be offered as a source").not.toContain(
    `ordinary spec ${stamp}`
  );

  // An unknown kind is refused rather than reaching the enum column as a bare 500.
  const bad = await page.request.get(`${API}/api/workshops?workshopType=NONSENSE`, { headers: auth });
  expect(bad.status()).toBe(422);

  // And the picker on the design-workshop page offers it.
  await signIn(page);
  await page.goto("/design-workshops");
  await page.getByRole("button", { name: /new design workshop/i }).click();

  // `.first()`: the FieldBlock's visible label and the dropdown's own ariaLabel both carry this
  // text, which is correct for a screen reader and ambiguous for a locator.
  const picker = page.getByLabel(/start from a recorded workshop/i).first();
  await expect(picker, "the create form offers a source workshop").toBeVisible({ timeout: 30_000 });
  await expect(page.getByText(/only workshops filed as a design & prototype/i)).toBeVisible();
});

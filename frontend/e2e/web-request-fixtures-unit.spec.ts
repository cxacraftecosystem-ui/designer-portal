import { readFileSync, writeFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import { newAccountBody } from "@/app/(protected)/users/accountAdmin";
import { designWorkshopCreateBody, stageSaveBody } from "@/lib/designWorkshopBodies";
import { designerRosterCreateBody, fullDesignerProfileBody } from "@/lib/designers";
import {
  NO_EXIF,
  artisanBody,
  craftBody,
  processBody,
  productBody,
  questionnaireCreateBody,
  toolBody,
  workshopBody
} from "@/lib/recordPayloads";
import { ARTISAN_FORM_CONTRACT } from "@/lib/artisanFormContract";
import { passwordAccountBody } from "@/lib/signIn";
import { DERIVED_NAME_SOURCES, writeBody, type WriteMethod } from "@/lib/writeBody";

const FIXTURE = join(__dirname, "..", "..", "shared", "web-request-fixtures.json");

type Variant = "filled" | "blank-optionals";
type Entry = { method: WriteMethod; path: string; variant: Variant; body: unknown };

const BLANK = "  ";
const RECORDED_AT = "2026-11-10T05:30:00.000Z";
const LINKS = { workshopId: "workshop-1", designWorkshopId: "design-workshop-1" };
const NO_DIMENSIONS = { height: "", width: "", length: "", breadth: "", heightInches: "" };

function formOf(values: Record<string, string>): FormData {
  const form = new FormData();
  for (const [key, value] of Object.entries(values)) form.append(key, value);
  return form;
}

const PLACE_FILLED = {
  latitude: "27.1751",
  longitude: "78.0421",
  altitude: "171",
  accuracy: "6",
  locationAddress: "Tajganj, Agra, Uttar Pradesh 282001",
  placeName: "Tajganj",
  capturedAt: RECORDED_AT,
  state: "Uttar Pradesh",
  district: "Agra",
  village: "Tajganj",
  pincode: "282001",
  subjectLatitude: "27.1749",
  subjectLongitude: "78.0418",
  recordedAt: RECORDED_AT,
  recordedTimezone: "Asia/Kolkata"
};

const PLACE_BLANK = {
  latitude: "27.1751",
  longitude: "78.0421",
  altitude: "",
  accuracy: "",
  locationAddress: BLANK,
  placeName: BLANK,
  capturedAt: RECORDED_AT,
  state: "Uttar Pradesh",
  district: "Agra",
  village: BLANK,
  pincode: BLANK,
  subjectLatitude: "",
  subjectLongitude: "",
  recordedAt: RECORDED_AT,
  recordedTimezone: "Asia/Kolkata"
};

const TOOL_FILLED = {
  ...PLACE_FILLED,
  place: "Agra",
  artisanName: "Ramesh Kumar",
  toolkitName: "Zardozi adda frame",
  localName: "Adda",
  englishName: "Embroidery frame",
  processUsedIn: "Stretching the base cloth",
  material: "Sheesham wood",
  materialFamily: "WOOD",
  yearsInUse: "12",
  thickness: "2",
  weight: "3.5",
  radius: "1",
  maker: "CARPENTER",
  traditionType: "TRADITIONAL",
  replacementCost: "1800",
  suggestionsForToolImprovement: "Add a tilting stand for the shoulders.",
  remarks: "Shared by three artisans in the unit.",
  status: "PENDING"
};

const TOOL_BLANK = {
  ...PLACE_BLANK,
  place: BLANK,
  artisanName: BLANK,
  toolkitName: "Zardozi adda frame",
  localName: BLANK,
  englishName: BLANK,
  processUsedIn: BLANK,
  material: BLANK,
  materialFamily: "",
  yearsInUse: "",
  thickness: "",
  weight: "",
  radius: "",
  maker: "",
  traditionType: "",
  replacementCost: "",
  suggestionsForToolImprovement: BLANK,
  remarks: BLANK,
  status: ""
};

const PRODUCT_FILLED = {
  ...PLACE_FILLED,
  place: "Agra",
  artisanName: "Ramesh Kumar",
  productName: "Zardozi clutch",
  localName: "Batua",
  productType: "FINISHED_GOOD",
  timeTakenToCompleteProduct: "4 days",
  size: "Medium",
  costOfMaking: "650",
  sellingPrice: "1400",
  marketDemand: "HIGH",
  rawMaterialsUsed: "Velvet, zari thread, sequins",
  mainToolsUsed: "Adda frame, aari needle",
  productFunctionUse: "Evening bag",
  remarks: "Sold through the Agra cluster store.",
  status: "PENDING"
};

const PRODUCT_BLANK = {
  ...PLACE_BLANK,
  place: BLANK,
  artisanName: BLANK,
  productName: "Zardozi clutch",
  localName: BLANK,
  productType: "",
  timeTakenToCompleteProduct: BLANK,
  size: BLANK,
  costOfMaking: "",
  sellingPrice: "",
  marketDemand: "",
  rawMaterialsUsed: BLANK,
  mainToolsUsed: BLANK,
  productFunctionUse: BLANK,
  remarks: BLANK,
  status: ""
};

const ARTISAN_FILLED = {
  ...PLACE_FILLED,
  name: "Ramesh Kumar",
  localName: "रमेश कुमार",
  gender: "Male",
  dateOfBirth: "1978-04-12",
  craftStartDate: "1994-06-01",
  experienceYears: "32",
  experienceMonths: "5",
  phone: "9876543210",
  phoneCountryCode: "+91",
  email: "ramesh.kumar@example.in",
  place: "Agra",
  address: "14 Tajganj Road, Agra",
  notes: "Master craftsman, trains apprentices.",
  aadhaarNumber: "234123412346",
  pehchanCardAvailable: "true",
  pehchanCardNumber: "UPAGR1234567",
  dos: "1. Keep the thread dry",
  donts: "1. Do not wash in hot water",
  craftId: "craft-1",
  status: "PENDING"
};

const ARTISAN_BLANK = {
  ...PLACE_BLANK,
  name: "Ramesh Kumar",
  localName: BLANK,
  gender: "",
  dateOfBirth: "1978-04-12",
  craftStartDate: "1994-06-01",
  experienceYears: "",
  experienceMonths: "",
  phone: BLANK,
  phoneCountryCode: "",
  email: BLANK,
  place: "Agra",
  address: BLANK,
  notes: BLANK,
  aadhaarNumber: BLANK,
  pehchanCardAvailable: "false",
  pehchanCardNumber: BLANK,
  dos: "1. Keep the thread dry",
  donts: "1. Do not wash in hot water",
  craftId: "craft-1",
  status: ""
};

const WORKSHOP_FILLED = {
  ...PLACE_FILLED,
  title: "Agra Zardozi Cluster Workshop",
  date: "2026-11-10",
  startDate: "2026-11-10",
  endDate: "2026-11-24",
  place: "Agra",
  description: "Fourteen-day design and prototype workshop.",
  notes: "Venue: Tajganj community hall.",
  status: "APPROVED"
};

const WORKSHOP_BLANK = {
  ...PLACE_BLANK,
  title: "Agra Zardozi Cluster Workshop",
  date: "",
  startDate: "",
  endDate: "",
  place: "Agra",
  description: BLANK,
  notes: BLANK,
  status: "APPROVED"
};

const CRAFT_FILLED = {
  name: "Zardozi",
  localName: "ज़रदोज़ी",
  subCategoryId: "craft-subcategory-1",
  description: "Metal-thread embroidery on velvet and silk."
};

const CRAFT_BLANK = { name: "Zardozi", localName: BLANK, subCategoryId: "craft-subcategory-1", description: BLANK };

function toolEntries(): Entry[] {
  const filled = {
    craftName: "Zardozi",
    craftIds: ["craft-1"],
    artisanIds: ["artisan-1"],
    dimensions: { height: "30", width: "60", length: "36", breadth: "24", heightInches: "12" },
    accepted: {},
    ...LINKS,
    exif: NO_EXIF
  };
  const blank = { ...filled, craftName: BLANK, dimensions: NO_DIMENSIONS, workshopId: null, designWorkshopId: null };
  return pairs("/api/tools", "/api/tools/{tool_id}", (variant, edit) =>
    toolBody(formOf(variant === "filled" ? TOOL_FILLED : TOOL_BLANK), {
      ...(variant === "filled" ? filled : blank),
      initialStatus: edit ? "PENDING" : null
    })
  );
}

function productEntries(): Entry[] {
  const filled = {
    artisanId: "artisan-1",
    craftId: "craft-1",
    dimensions: { length: "10", breadth: "2", height: "7" },
    accepted: {},
    ...LINKS,
    exif: NO_EXIF
  };
  const blank = { ...filled, dimensions: { length: "", breadth: "", height: "" }, workshopId: null, designWorkshopId: null };
  return pairs("/api/products", "/api/products/{product_id}", (variant, edit) =>
    productBody(formOf(variant === "filled" ? PRODUCT_FILLED : PRODUCT_BLANK), {
      ...(variant === "filled" ? filled : blank),
      initialStatus: edit ? "PENDING" : null
    })
  );
}

function processEntries(): Entry[] {
  const now = new Date(RECORDED_AT);
  return pairs("/api/processes", "/api/processes/{process_id}", (variant, edit) =>
    processBody(
      variant === "filled"
        ? {
            name: "Zardozi on velvet",
            productId: "product-1",
            ...LINKS,
            preProcessAvailable: true,
            notes: "Traced from a paper stencil with chalk.",
            status: "PENDING",
            steps: [
              { serverId: edit ? "process-step-1" : undefined, name: "Trace the motif", stepType: "MAIN", recordAdditional: true, notes: "Chalk and kerosene paste." },
              { name: "Fill with zari", stepType: "MAIN", recordAdditional: false, notes: "" }
            ],
            isEdit: edit,
            now
          }
        : {
            name: "Zardozi on velvet",
            productId: "product-1",
            workshopId: null,
            designWorkshopId: null,
            preProcessAvailable: false,
            notes: BLANK,
            status: "PENDING",
            steps: [{ name: "Trace the motif", stepType: "MAIN", recordAdditional: true, notes: BLANK }],
            isEdit: edit,
            now
          }
    )
  );
}

function artisanEntries(): Entry[] {
  return pairs("/api/artisans", "/api/artisans/{artisan_id}", (variant, edit) =>
    artisanBody(formOf(variant === "filled" ? ARTISAN_FILLED : ARTISAN_BLANK), {
      craftId: "craft-1",
      ...(variant === "filled" ? LINKS : { workshopId: null, designWorkshopId: null }),
      initialStatus: edit ? "PENDING" : null,
      formContract: ARTISAN_FORM_CONTRACT,
      ...(edit ? {} : { duplicateAcknowledged: variant === "filled" ? ["artisan-2"] : [] }),
      exif: NO_EXIF
    })
  );
}

function craftEntries(): Entry[] {
  return pairs("/api/crafts", "/api/crafts/{craft_id}", (variant) =>
    craftBody(formOf(variant === "filled" ? CRAFT_FILLED : CRAFT_BLANK), {
      stateOfOrigin: variant === "filled" ? ["Uttar Pradesh"] : [],
      stateOfPractice: variant === "filled" ? ["Uttar Pradesh", "Delhi"] : [],
      giStatus: variant === "filled" ? "GI" : "NON_GI",
      workshopId: variant === "filled" ? "workshop-1" : "",
      exif: NO_EXIF
    })
  );
}

function workshopEntries(): Entry[] {
  return pairs("/api/workshops", "/api/workshops/{workshop_id}", (variant) =>
    workshopBody(formOf(variant === "filled" ? WORKSHOP_FILLED : WORKSHOP_BLANK), {
      workshopType: "DESIGN_PROTOTYPE",
      canSetStatus: true,
      artisanIds: variant === "filled" ? ["artisan-1", "artisan-2"] : [],
      craftIds: variant === "filled" ? ["craft-1"] : []
    })
  );
}

function pairs(createPath: string, updatePath: string, build: (variant: Variant, edit: boolean) => unknown): Entry[] {
  const entries: Entry[] = [];
  for (const [method, path, edit] of [["POST", createPath, false], ["PATCH", updatePath, true]] as const) {
    for (const variant of ["filled", "blank-optionals"] as const) {
      entries.push({ method, path, variant, body: writeBody(build(variant, edit), method) });
    }
  }
  return entries;
}

function single(method: WriteMethod, path: string, filled: unknown, blank: unknown): Entry[] {
  return [
    { method, path, variant: "filled", body: filled },
    { method, path, variant: "blank-optionals", body: blank }
  ];
}

function otherEntries(): Entry[] {
  const designWorkshop = {
    title: "Design & Prototype Development Workshop — Agra Zardozi",
    templateId: "DCH_STANDARD",
    workshopKind: "DESIGN_PROTOTYPE_DEVELOPMENT",
    designerUserId: "user-designer-1",
    designerUserIds: ["user-designer-1", "user-designer-2"],
    craftName: "Zardozi",
    clusterName: "Agra Zardozi Cluster",
    state: "Uttar Pradesh",
    district: "Agra",
    startDate: "2026-11-10",
    endDate: "2026-11-24",
    workshopId: "workshop-1",
    notes: "Sanctioned under the NHDP."
  };
  const designWorkshopBlank = {
    title: "Design & Prototype Development Workshop — Agra Zardozi",
    templateId: "DCH_STANDARD",
    workshopKind: "DESIGN_PROTOTYPE_DEVELOPMENT",
    designerUserId: "",
    designerUserIds: [],
    craftName: BLANK,
    clusterName: BLANK,
    state: "",
    district: "",
    startDate: "",
    endDate: "",
    workshopId: "",
    notes: BLANK
  };
  const stage = (data: Record<string, string>) =>
    stageSaveBody({ entries: [{ entityKey: "workshopSetup", data }] });
  const profile = {
    displayName: "Anita Sharma",
    localName: "अनीता शर्मा",
    designation: "Textile designer",
    institution: "NIFT Delhi",
    department: "Textile Design",
    qualification: "M.Des",
    specialisation: "Surface ornamentation",
    experienceYears: "11",
    experienceMonths: "4",
    biography: "Works with embroidery clusters in Uttar Pradesh.",
    phone: "9811122233",
    email: "anita.sharma@example.in",
    website: "https://example.in/anita",
    addressLine: "22 Sanjay Place",
    city: "Agra",
    state: "Uttar Pradesh",
    pincode: "282002",
    photoMediaId: "media-photo-1",
    signatureMediaId: "media-signature-1",
    cvMediaId: "media-cv-1",
    empanelmentNo: "DC-H/2026/0142",
    empanelmentDate: "2026-01-15"
  };
  const profileBlank = Object.fromEntries(Object.keys(profile).map((key) => [key, BLANK]));
  const account = (password: string, mode: "SET" | "GENERATE") =>
    passwordAccountBody(
      newAccountBody(
        {
          name: "Anita Sharma",
          email: "anita.sharma@example.in",
          role: "DESIGNER",
          passwordMode: mode,
          password,
          mustChangePassword: true,
          sendPasswordLink: false,
          canManageQuestionnaire: false,
          canDownloadDataset: false
        },
        { admin: true }
      )
    );
  return [
    ...single(
      "POST",
      "/api/questionnaires",
      writeBody(questionnaireCreateBody(formOf({ title: "Zardozi baseline", description: "Stage 5 interview guide." }), { designWorkshopId: "design-workshop-1", kind: "WORKSHOP_INTERVIEW" }), "POST"),
      writeBody(questionnaireCreateBody(formOf({ title: "Zardozi baseline", description: BLANK }), { designWorkshopId: "", kind: "" }), "POST")
    ),
    ...single("POST", "/api/design-workshops", designWorkshopCreateBody(designWorkshop), designWorkshopCreateBody(designWorkshopBlank)),
    ...single(
      "PUT",
      "/api/design-workshops/{workshop_id}/stages/{stage_key}",
      stage({ workshopTitle: designWorkshop.title, schemeName: "National Handicrafts Development Programme", craftName: "Zardozi", craftLocalName: "ज़रदोज़ी" }),
      stage({ workshopTitle: designWorkshop.title, schemeName: "National Handicrafts Development Programme", craftName: "Zardozi", craftLocalName: "" })
    ),
    ...single("PUT", "/api/designers/me/profile", fullDesignerProfileBody(profile), fullDesignerProfileBody(profileBlank)),
    ...single(
      "POST",
      "/api/designers/roster",
      designerRosterCreateBody({ email: "anita.sharma@example.in", fullName: "Anita Sharma", institution: "NIFT Delhi", notes: "Empanelled 2026.", isActive: true }),
      designerRosterCreateBody({ email: "anita.sharma@example.in", fullName: BLANK, institution: BLANK, notes: BLANK, isActive: true })
    ),
    ...single("POST", "/api/users", account("Agra@2026!", "SET"), account("", "GENERATE"))
  ];
}

function allEntries(): Entry[] {
  return [
    ...toolEntries(),
    ...productEntries(),
    ...processEntries(),
    ...artisanEntries(),
    ...craftEntries(),
    ...workshopEntries(),
    ...otherEntries()
  ];
}

function rendered(): string {
  return `${JSON.stringify(allEntries(), null, 2)}\n`;
}

function blankStrings(value: unknown, at = "body"): string[] {
  if (typeof value === "string") return value.trim() ? [] : [at];
  if (Array.isArray(value)) return value.flatMap((item, index) => blankStrings(item, `${at}[${index}]`));
  if (value && typeof value === "object") return Object.entries(value).flatMap(([key, item]) => blankStrings(item, `${at}.${key}`));
  return [];
}

test("the committed fixture matches what the web builders send", () => {
  const expected = rendered();
  if (process.env.UPDATE_WEB_REQUEST_FIXTURES === "1") writeFileSync(FIXTURE, expected);
  const committed = readFileSync(FIXTURE, "utf8").replace(/\r\n/g, "\n");
  expect(committed, "shared/web-request-fixtures.json is stale. Regenerate: UPDATE_WEB_REQUEST_FIXTURES=1 npx playwright test e2e/web-request-fixtures-unit.spec.ts").toBe(expected);
});

test("no create sends a blank text field", () => {
  for (const entry of allEntries().filter((candidate) => candidate.method === "POST")) {
    expect(blankStrings(entry.body), `${entry.path} ${entry.variant}`).toEqual([]);
  }
});

test("no write sends a blank derivable name while its id is set", () => {
  for (const entry of allEntries()) {
    const body = entry.body as Record<string, unknown>;
    for (const [name, sources] of Object.entries(DERIVED_NAME_SOURCES)) {
      const linked = sources.some((key) => {
        const value = body[key];
        return (typeof value === "string" && value.trim() !== "") || (Array.isArray(value) && value.length > 0);
      });
      if (!linked || !(name in body)) continue;
      expect(typeof body[name] === "string" && (body[name] as string).trim() !== "", `${entry.method} ${entry.path} ${entry.variant} ${name}`).toBe(true);
    }
  }
});

test("every record type is covered for create and update, filled and blank", () => {
  const keys = new Set(allEntries().map((entry) => `${entry.method} ${entry.path} ${entry.variant}`));
  for (const [create, update] of [
    ["/api/tools", "/api/tools/{tool_id}"],
    ["/api/products", "/api/products/{product_id}"],
    ["/api/processes", "/api/processes/{process_id}"],
    ["/api/artisans", "/api/artisans/{artisan_id}"],
    ["/api/crafts", "/api/crafts/{craft_id}"],
    ["/api/workshops", "/api/workshops/{workshop_id}"]
  ]) {
    for (const variant of ["filled", "blank-optionals"]) {
      expect(keys.has(`POST ${create} ${variant}`)).toBe(true);
      expect(keys.has(`PATCH ${update} ${variant}`)).toBe(true);
    }
  }
});

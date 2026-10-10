import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import { MODEL_FORMATS, modelFormatLabel, modelFormatOf } from "@/lib/modelFormats";
import { ModelViewerError, parseModel } from "@/lib/modelViewerCore";

// @ts-ignore — a plain .mjs helper shared with the bundling script
import { modelViewerSourceHash } from "../scripts/model-viewer-source-hash.mjs";

/**
 * THE 3D MODEL VIEWER — which files are models, that the loaders really draw them, and that the
 * handset's copy of the viewer is the web's.
 */

const FRONTEND = join(__dirname, "..");
const ANDROID_MAIN = join(FRONTEND, "..", "android", "app", "src", "main");

const encode = (text: string): ArrayBuffer => new TextEncoder().encode(text).buffer as ArrayBuffer;

test.describe("which files are 3D models", () => {
  test("by extension first, whatever the browser called the type", () => {
    expect(modelFormatOf("chair.GLB", "application/octet-stream")).toBe("glb");
    expect(modelFormatOf("scan.ply", "")).toBe("ply");
    expect(modelFormatOf("print.3mf", null)).toBe("3mf");
    expect(modelFormatOf("iphone-scan.usdz", undefined)).toBe("usdz");
    expect(modelFormatOf("sheet.pdf", "application/pdf")).toBeNull();
    expect(modelFormatOf("notes.txt", "text/plain")).toBeNull();
  });

  test("by a model/* type when the name says nothing", () => {
    expect(modelFormatOf("upload", "model/gltf-binary")).toBe("glb");
    expect(modelFormatOf("upload", "model/stl")).toBe("stl");
    expect(modelFormatOf(null, "model/vnd.usdz+zip")).toBe("usdz");
  });

  test("every format the upload card offers is one the viewer draws", () => {
    const card = readFileSync(join(FRONTEND, "components", "sketches", "upload", "PrototypeModelField.tsx"), "utf8");
    const offered = [...card.matchAll(/\{ ext: "([a-z0-9]+)"/g)].map((match) => match[1]);
    expect(offered.length).toBeGreaterThan(0);
    expect([...offered].sort()).toEqual([...MODEL_FORMATS].sort());
    expect(modelFormatLabel("gltf")).toBe("glTF");
    expect(modelFormatLabel("3mf")).toBe("3MF");
  });
});

test.describe("the loaders draw real files", () => {
  test("an ASCII STL triangle becomes a mesh", async () => {
    const stl = "solid t\nfacet normal 0 0 1\nouter loop\nvertex 0 0 0\nvertex 1 0 0\nvertex 0 1 0\nendloop\nendfacet\nendsolid t\n";
    const object = await parseModel(encode(stl), "stl");
    expect((object as unknown as { isMesh?: boolean }).isMesh).toBe(true);
  });

  test("an OBJ square becomes a group with a mesh in it", async () => {
    const obj = "v 0 0 0\nv 1 0 0\nv 1 1 0\nv 0 1 0\nf 1 2 3 4\n";
    const object = await parseModel(encode(obj), "obj");
    let meshes = 0;
    object.traverse((node) => {
      if ((node as unknown as { isMesh?: boolean }).isMesh) meshes += 1;
    });
    expect(meshes).toBe(1);
  });

  test("a PLY with vertices and no faces is drawn as points", async () => {
    const ply = "ply\nformat ascii 1.0\nelement vertex 3\nproperty float x\nproperty float y\nproperty float z\nend_header\n0 0 0\n1 0 0\n0 1 0\n";
    const object = await parseModel(encode(ply), "ply");
    expect((object as unknown as { isPoints?: boolean }).isPoints).toBe(true);
  });

  test("a damaged file is a sentence, not a parser's stack", async () => {
    await expect(parseModel(encode("not a model at all"), "glb")).rejects.toBeInstanceOf(ModelViewerError);
    await expect(parseModel(encode("{}"), "gltf")).rejects.toThrow(/export the model as \.glb/);
  });
});

test.describe("the handset draws the same viewer", () => {
  const bundle = readFileSync(join(ANDROID_MAIN, "assets", "model-viewer", "viewer.js"), "utf8");
  const header = bundle.slice(0, bundle.indexOf("\n"));

  test("the bundle was built from the current sources — re-run scripts/build-android-model-viewer.mjs if not", () => {
    expect(header).toContain(`sources@${modelViewerSourceHash(FRONTEND)}`);
  });

  test("the bundle carries the three.js the web installs", () => {
    const pkg = JSON.parse(readFileSync(join(FRONTEND, "package.json"), "utf8")) as { dependencies: Record<string, string> };
    const lock = JSON.parse(readFileSync(join(FRONTEND, "package-lock.json"), "utf8")) as {
      packages: Record<string, { version?: string }>;
    };
    const installed = lock.packages["node_modules/three"]?.version;
    expect(pkg.dependencies.three).toBeTruthy();
    expect(header).toContain(`three@${installed}`);
  });

  test("the handset's page loads the bundle, and its format list is the web's", () => {
    const page = readFileSync(join(ANDROID_MAIN, "assets", "model-viewer", "index.html"), "utf8");
    expect(page).toContain('<script src="viewer.js"></script>');
    const kotlin = readFileSync(
      join(ANDROID_MAIN, "java", "com", "designprototype", "workshop", "ui", "designworkshop", "DwModelViewer.kt"),
      "utf8"
    );
    const declared = /DW_MODEL_FORMATS: List<String> = listOf\(([^)]*)\)/.exec(kotlin)?.[1] ?? "";
    expect([...declared.matchAll(/"([a-z0-9]+)"/g)].map((match) => match[1])).toEqual([...MODEL_FORMATS]);
  });
});

/**
 * THE 3D MODEL VIEWER — one framework-free module, drawn by BOTH clients.
 *
 * The web mounts it from `components/media/ModelViewer.tsx` through a dynamic import, so three.js is
 * fetched only by a page that is actually showing a model. The handset draws the SAME module: it is
 * bundled by `scripts/build-android-model-viewer.mjs` into
 * `android/app/src/main/assets/model-viewer/viewer.js`, which a WebView in `DwModelViewer.kt` loads
 * from the app's own assets. One source, two hosts — so a model that turns on a laptop turns the same
 * way on a phone, and a loader fixed here is fixed in both once the bundle is rebuilt.
 * `e2e/model-viewer-unit.spec.ts` holds the bundle to this file's hash, so a change here that is not
 * rebuilt for the handset fails the web unit run rather than drifting silently.
 *
 * WHAT IT DRAWS: every format in `MODEL_FORMATS`, each with the loader three.js ships for it. A glTF
 * whose meshes or textures sit in separate files cannot be drawn from a single stored file, and the
 * refusal says so and names the .glb export as the fix — the upload card already advises that format.
 *
 * WHAT IT DOES NOT DO: nothing here uploads, edits or converts a model. It reads the bytes it is
 * given and draws them; the stored file is untouched.
 */

import {
  AmbientLight,
  Box3,
  BufferGeometry,
  Color,
  DirectionalLight,
  DoubleSide,
  Group,
  HemisphereLight,
  Material,
  Mesh,
  MeshStandardMaterial,
  Object3D,
  PerspectiveCamera,
  Points,
  PointsMaterial,
  SRGBColorSpace,
  Scene,
  Vector3,
  WebGLRenderer
} from "three";
import { OrbitControls } from "three/examples/jsm/controls/OrbitControls.js";
import { FBXLoader } from "three/examples/jsm/loaders/FBXLoader.js";
import { GLTFLoader } from "three/examples/jsm/loaders/GLTFLoader.js";
import { OBJLoader } from "three/examples/jsm/loaders/OBJLoader.js";
import { PLYLoader } from "three/examples/jsm/loaders/PLYLoader.js";
import { STLLoader } from "three/examples/jsm/loaders/STLLoader.js";
import { ThreeMFLoader } from "three/examples/jsm/loaders/3MFLoader.js";
import { USDLoader } from "three/examples/jsm/loaders/USDLoader.js";

import type { ModelFormat } from "./modelFormats";

/** A refusal written for the person looking at the screen. */
export class ModelViewerError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "ModelViewerError";
  }
}

export type ModelViewerHandle = {
  /** Put the camera back where it started. */
  reset(): void;
  /** Release the GPU context, the controls and every geometry and material. Safe to call twice. */
  dispose(): void;
};

/** The neutral grey a model is drawn in when its file carries no material of its own. */
const PLAIN_MATERIAL_COLOUR = 0xb8b2a7;

const BACKGROUND = 0xf4f2ee;

function plainMaterial(vertexColors: boolean): MeshStandardMaterial {
  return new MeshStandardMaterial({
    color: vertexColors ? 0xffffff : PLAIN_MATERIAL_COLOUR,
    vertexColors,
    roughness: 0.75,
    metalness: 0.05,
    side: DoubleSide
  });
}

/** A loose geometry as a mesh, lit by its own normals or by ones computed from its faces. */
function objectFromGeometry(geometry: BufferGeometry): Object3D {
  if (!geometry.getAttribute("normal")) geometry.computeVertexNormals();
  return new Mesh(geometry, plainMaterial(Boolean(geometry.getAttribute("color"))));
}

/** The text a parser wants, from the bytes every format arrives as. */
function asText(buffer: ArrayBuffer): string {
  return new TextDecoder().decode(buffer);
}

/**
 * Turn stored bytes into a scene object. Every refusal is a sentence a designer can act on; a
 * parser's own exception text is never shown, because it names internals and not the file.
 */
export async function parseModel(buffer: ArrayBuffer, format: ModelFormat): Promise<Object3D> {
  try {
    switch (format) {
      case "glb":
      case "gltf": {
        const gltf = await new GLTFLoader().parseAsync(buffer, "");
        return gltf.scene;
      }
      case "stl":
        return objectFromGeometry(new STLLoader().parse(buffer));
      case "ply": {
        const geometry = new PLYLoader().parse(buffer);
        if (!geometry.index && geometry.getAttribute("position") && !geometry.getAttribute("normal")) {
          // A PLY with no faces is a photogrammetry point cloud, and it is drawn as points rather
          // than as a mesh of nonsense triangles.
          return new Points(
            geometry,
            new PointsMaterial({ size: 2, vertexColors: Boolean(geometry.getAttribute("color")), sizeAttenuation: false })
          );
        }
        return objectFromGeometry(geometry);
      }
      case "obj":
        return new OBJLoader().parse(asText(buffer));
      case "3mf":
        return new ThreeMFLoader().parse(buffer);
      case "fbx":
        return new FBXLoader().parse(buffer, "");
      case "usdz":
        return new USDLoader().parse(buffer);
    }
  } catch {
    if (format === "gltf") {
      throw new ModelViewerError(
        "This glTF file could not be drawn. A .gltf usually keeps its mesh and textures in separate files, and only this one file is stored — export the model as .glb, which holds everything in one file, and attach that instead."
      );
    }
    throw new ModelViewerError(
      "This file could not be drawn as a 3D model — it may be damaged, or saved in a variant of the format this viewer does not read. It is still stored and can be downloaded and opened in a 3D program."
    );
  }
  throw new ModelViewerError("This kind of file cannot be drawn as a 3D model.");
}

/** Draw both faces of every surface: scans and print files often have inward-facing normals. */
function prepare(root: Object3D): void {
  root.traverse((node) => {
    const mesh = node as Mesh;
    if (!mesh.isMesh) return;
    const materials = Array.isArray(mesh.material) ? mesh.material : [mesh.material];
    for (const material of materials) {
      if (material) (material as Material).side = DoubleSide;
    }
  });
}

/** Everything a scene holds that owns GPU memory, released. */
function disposeTree(root: Object3D): void {
  root.traverse((node) => {
    const drawable = node as Mesh;
    drawable.geometry?.dispose?.();
    const materials = Array.isArray(drawable.material) ? drawable.material : drawable.material ? [drawable.material] : [];
    for (const material of materials) {
      for (const value of Object.values(material)) {
        if (value && typeof value === "object" && "isTexture" in value) (value as { dispose(): void }).dispose();
      }
      material.dispose();
    }
  });
}

/**
 * Draw `buffer` into `container` and hand back a handle. The container must already be laid out —
 * the canvas takes its size, and follows it as it changes.
 */
export async function mountModelViewer(
  container: HTMLElement,
  buffer: ArrayBuffer,
  format: ModelFormat
): Promise<ModelViewerHandle> {
  const model = await parseModel(buffer, format);
  prepare(model);

  const bounds = new Box3().setFromObject(model);
  if (bounds.isEmpty()) {
    disposeTree(model);
    throw new ModelViewerError("This file opened, but it holds no shape that can be drawn.");
  }

  let renderer: WebGLRenderer;
  try {
    renderer = new WebGLRenderer({ antialias: true });
  } catch {
    disposeTree(model);
    throw new ModelViewerError(
      "This device's browser cannot draw 3D graphics, so the model cannot be shown here. Download it and open it in a 3D program."
    );
  }
  renderer.outputColorSpace = SRGBColorSpace;
  renderer.setPixelRatio(Math.min(globalThis.devicePixelRatio || 1, 2));
  renderer.domElement.style.display = "block";
  renderer.domElement.style.width = "100%";
  renderer.domElement.style.height = "100%";
  renderer.domElement.style.touchAction = "none";
  container.appendChild(renderer.domElement);

  const scene = new Scene();
  scene.background = new Color(BACKGROUND);
  scene.add(new HemisphereLight(0xffffff, 0x8d8a85, 1.6));
  scene.add(new AmbientLight(0xffffff, 0.35));
  const key = new DirectionalLight(0xffffff, 1.8);
  key.position.set(1, 1.6, 1.2);
  scene.add(key);
  const fill = new DirectionalLight(0xffffff, 0.6);
  fill.position.set(-1.2, 0.4, -1);
  scene.add(fill);

  // THE MODEL IS CENTRED AT THE ORIGIN AND THE CAMERA IS SET BACK BY ITS SIZE, so a scan in metres
  // and a print file in millimetres both open filling the frame rather than as a speck or a wall.
  const holder = new Group();
  const centre = bounds.getCenter(new Vector3());
  model.position.sub(centre);
  holder.add(model);
  scene.add(holder);

  const size = bounds.getSize(new Vector3());
  const radius = Math.max(size.length() / 2, 1e-6);
  const camera = new PerspectiveCamera(40, 1, radius / 100, radius * 100);
  const home = new Vector3(radius * 1.4, radius * 0.9, radius * 1.9);
  camera.position.copy(home);
  camera.lookAt(0, 0, 0);

  const controls = new OrbitControls(camera, renderer.domElement);
  controls.enableDamping = false;
  controls.minDistance = radius * 0.2;
  controls.maxDistance = radius * 20;
  controls.target.set(0, 0, 0);
  controls.update();

  let disposed = false;
  let frame = 0;
  const render = () => {
    frame = 0;
    if (!disposed) renderer.render(scene, camera);
  };
  // RENDERED ON DEMAND, not every frame: a still model costs nothing while nobody is turning it, which
  // matters on a phone's battery.
  const requestRender = () => {
    if (!frame && !disposed) frame = requestAnimationFrame(render);
  };
  controls.addEventListener("change", requestRender);

  const resize = () => {
    const width = Math.max(container.clientWidth, 1);
    const height = Math.max(container.clientHeight, 1);
    renderer.setSize(width, height, false);
    camera.aspect = width / height;
    camera.updateProjectionMatrix();
    requestRender();
  };
  const observer = typeof ResizeObserver === "undefined" ? null : new ResizeObserver(resize);
  observer?.observe(container);
  resize();

  return {
    reset() {
      camera.position.copy(home);
      controls.target.set(0, 0, 0);
      controls.update();
      requestRender();
    },
    dispose() {
      if (disposed) return;
      disposed = true;
      if (frame) cancelAnimationFrame(frame);
      observer?.disconnect();
      controls.removeEventListener("change", requestRender);
      controls.dispose();
      disposeTree(model);
      renderer.dispose();
      renderer.forceContextLoss();
      renderer.domElement.remove();
    }
  };
}

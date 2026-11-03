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

export class ModelViewerError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "ModelViewerError";
  }
}

export type ModelViewerHandle = {
  reset(): void;
  dispose(): void;
};

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

function objectFromGeometry(geometry: BufferGeometry): Object3D {
  if (!geometry.getAttribute("normal")) geometry.computeVertexNormals();
  return new Mesh(geometry, plainMaterial(Boolean(geometry.getAttribute("color"))));
}

function asText(buffer: ArrayBuffer): string {
  return new TextDecoder().decode(buffer);
}

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
        "Can't show this .gltf — export the model as .glb and attach that."
      );
    }
    throw new ModelViewerError(
      "Can't show this model. It's still saved — download it to open in a 3D program."
    );
  }
  throw new ModelViewerError("This kind of file cannot be drawn as a 3D model.");
}

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

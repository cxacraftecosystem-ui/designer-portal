import { createRequire } from "node:module";
import { readdirSync, readFileSync, writeFileSync, mkdirSync, existsSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

const KIT = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const ASSETS = join(KIT, "assets");
const out = resolve(process.argv[2] || "readme-preview");
const mermaidDir = process.argv[3] ? resolve(process.argv[3]) : null;
const require = createRequire(join(resolve(process.env.PUPPETEER_DIR || "node_modules"), "noop.js"));
const puppeteer = require("puppeteer");

mkdirSync(out, { recursive: true });
const WIDTH = 1012;
const url = (p) => pathToFileURL(p).href;
const order = ["hero", "stats", "divider", "cards", "pipeline", "timeline", "footer"];
const icons = readdirSync(join(ASSETS, "icons")).filter((f) => f.endsWith(".svg")).sort();
const diagrams = mermaidDir && existsSync(mermaidDir) ? readdirSync(mermaidDir).filter((f) => f.endsWith(".svg")).sort() : [];

function size(file) {
  const head = readFileSync(file, "utf8").slice(0, 400);
  const w = Number((head.match(/\swidth="([\d.]+)"/) || [])[1] || 1200);
  const h = Number((head.match(/\sheight="([\d.]+)"/) || [])[1] || 400);
  return { w, h };
}

function embed(file, framed) {
  if (!framed) return `<img src="${url(file)}" width="100%">`;
  const { w, h } = size(file);
  const k = WIDTH / w;
  return `<iframe src="${url(file)}" scrolling="no" style="border:0;display:block;width:${w}px;height:${h}px;zoom:${k}"></iframe>`;
}

function page(mode, framed) {
  const bg = mode === "dark" ? "#0d1117" : "#ffffff";
  const fg = mode === "dark" ? "#e6edf3" : "#1f2328";
  const blocks = order
    .filter((n) => existsSync(join(ASSETS, `${n}-${mode}.svg`)))
    .map((n) => `<h3>${n}</h3>${embed(join(ASSETS, `${n}-${mode}.svg`), framed)}`)
    .join("\n");
  const iconRow = icons.map((f) => `<figure><img src="${url(join(ASSETS, "icons", f))}" width="40"><figcaption>${f.replace(".svg", "")}</figcaption></figure>`).join("");
  const mm = framed ? "" : diagrams.map((f) => `<h3>${f}</h3><img src="${url(join(mermaidDir, f))}" style="max-width:100%">`).join("\n");
  return `<!doctype html><meta charset="utf-8"><style>body{margin:0;padding:24px 32px;background:${bg};color:${fg};font:14px -apple-system,'Segoe UI',Helvetica,Arial,sans-serif;width:${WIDTH}px}h3{margin:28px 0 8px;font-size:13px;opacity:.6}figure{display:inline-block;margin:6px;text-align:center;width:88px}figcaption{font-size:10px;opacity:.7}</style>
${blocks}
<h3>icons</h3><div>${iconRow}</div>
${mm}`;
}

const browser = await puppeteer.launch({ args: ["--no-sandbox", "--allow-file-access-from-files"] });
try {
  for (const mode of ["light", "dark"]) {
    for (const reduced of [false, true]) {
      const file = join(out, `preview-${mode}${reduced ? "-reduced" : ""}.html`);
      writeFileSync(file, page(mode, reduced));
      const tab = await browser.newPage();
      await tab.setViewport({ width: WIDTH + 64, height: 12000, deviceScaleFactor: 1 });
      if (reduced) await tab.emulateMediaFeatures([{ name: "prefers-reduced-motion", value: "reduce" }]);
      await tab.goto(url(file), { waitUntil: "load" });
      await new Promise((ok) => setTimeout(ok, reduced ? 800 : 6500));
      const png = join(out, `preview-${mode}${reduced ? "-reduced" : ""}.png`);
      await tab.screenshot({ path: png, fullPage: true });
      console.log(png);
      await tab.close();
    }
  }
} finally {
  await browser.close();
}

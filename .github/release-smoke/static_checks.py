#!/usr/bin/env python3
"""Read the R8-shrunk release APK for the things that are reached BY NAME, before any device sees it.

TEMPORARY, with the job that runs it. A shrunk build loses what nothing in the bytecode points at, and
everything below is pointed at from somewhere R8 cannot read:

  * classes named in the merged MANIFEST — components, androidx.startup initializers (OkHttp 5's
    PlatformInitializer is one), class names in <meta-data> values (CameraX's config provider) and in
    keys (ML Kit / Firebase component registrars);
  * classes named in META-INF/services — Coil 3 finds coil-network-okhttp and coil-video that way;
  * the native methods libsherpa-onnx-jni.so binds by their mangled Java_… names;
  * Credential Manager's provider, resolved at runtime by name;
  * every Retrofit interface, reached through a dynamic proxy.

It also prints which libraries' own consumer rules R8 applied, from AGP's configuration.txt.
Exit 1 on anything missing.
"""

from __future__ import annotations

import json
import os
import re
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path

APK = Path(os.environ["SMOKE_APK"])
MAPPING_DIR = Path(os.environ["SMOKE_MAPPING_DIR"])
BUILD_TOOLS = Path(os.environ["SMOKE_BUILD_TOOLS"])
OUT = Path(os.environ["SMOKE_OUT"])

failures: list[str] = []
report: dict = {}


def run(args: list[str]) -> str:
    done = subprocess.run(args, capture_output=True, text=True, errors="replace")
    if done.returncode != 0:
        raise SystemExit(f"{args[0]} failed ({done.returncode}): {done.stderr[-2000:]}")
    return done.stdout


def descriptor(name: str) -> str:
    return "L" + name.replace(".", "/") + ";"


# ── The signer: the SDK's throwaway debug key, never the release key ──────────────────────────
certs = run([str(BUILD_TOOLS / "apksigner"), "verify", "--print-certs", "-v", str(APK)])
signer = [line for line in certs.splitlines() if "certificate DN" in line]
report["signer"] = signer
print("\n".join(signer))
if not any("CN=Android Debug" in line for line in signer):
    failures.append("the APK is not signed with the SDK debug key: " + "; ".join(signer))

# ── Every class and method in the dex files, with access flags ─────────────────────────────────
classes: dict[str, dict[str, set[str]]] = {}
with tempfile.TemporaryDirectory() as scratch, zipfile.ZipFile(APK) as apk:
    names = apk.namelist()
    dexes = sorted(n for n in names if re.fullmatch(r"classes\d*\.dex", n))
    for dex in dexes:
        path = Path(scratch) / dex
        path.write_bytes(apk.read(dex))
        current = None
        section = None
        method = None
        for line in run([str(BUILD_TOOLS / "dexdump"), str(path)]).splitlines():
            stripped = line.strip()
            if stripped.startswith("Class descriptor"):
                current = stripped.split("'")[1]
                classes.setdefault(current, {"methods": set(), "native": set()})
                section = None
            elif stripped in ("Static fields     -", "Instance fields   -", "Direct methods    -",
                              "Virtual methods   -") or stripped.startswith(("Static fields", "Instance fields",
                                                                             "Direct methods", "Virtual methods")):
                section = "methods" if "methods" in stripped else "fields"
            elif current and section == "methods" and stripped.startswith("name") and ":" in stripped:
                method = stripped.split("'")[1]
                classes[current]["methods"].add(method)
            elif current and section == "methods" and stripped.startswith("access") and method:
                if "NATIVE" in stripped:
                    classes[current]["native"].add(method)
                method = None
    services = {n: apk.read(n).decode("utf-8", "replace") for n in names if n.startswith("META-INF/services/")}
    libraries = {n: apk.read(n) for n in names if n.endswith("libsherpa-onnx-jni.so")}
    for lib_name, data in libraries.items():
        (Path(scratch) / lib_name.replace("/", "_")).write_bytes(data)
    jni = {}
    for lib_name in libraries:
        syms = run(["readelf", "--dyn-syms", "-W", str(Path(scratch) / lib_name.replace("/", "_"))])
        exported = set()
        for row in syms.splitlines():
            parts = row.split()
            if len(parts) >= 8 and parts[3] == "FUNC" and parts[6] != "UND":
                exported.add(parts[7].split("@")[0])
        jni[lib_name] = exported
report["dexFiles"] = len(dexes)
report["classes"] = len(classes)
print(f"{len(dexes)} dex files, {len(classes)} classes")


def present(name: str) -> bool:
    return descriptor(name) in classes


# ── 1. Classes the merged manifest names ──────────────────────────────────────────────────────
tree = run([str(BUILD_TOOLS / "aapt2"), "dump", "xmltree", "--file", "AndroidManifest.xml", str(APK)])
(OUT / "release-manifest-xmltree.txt").write_text(tree, encoding="utf-8")
named: set[str] = set()
element = None
attrs: dict[str, str] = {}


def flush() -> None:
    if element in ("activity", "service", "provider", "receiver", "application", "activity-alias"):
        for key in ("name", "targetActivity"):
            if key in attrs:
                named.add(attrs[key])
    if element == "meta-data":
        name, value = attrs.get("name", ""), attrs.get("value", "")
        if value == "androidx.startup":
            named.add(name)
        if name.startswith("com.google.firebase.components:"):
            named.add(name.split(":", 1)[1])
        if re.fullmatch(r"[a-z][\w]*(\.[\w$]+)+\.[A-Z][\w$]*", value):
            named.add(value)


for line in tree.splitlines():
    stripped = line.strip()
    if stripped.startswith("E: "):
        flush()
        element = stripped[3:].split()[0]
        attrs = {}
    elif stripped.startswith("A: "):
        match = re.match(r'A: (?:http://schemas.android.com/apk/res/android:)?(\w+)\([^)]*\)="([^"]*)"', stripped)
        if match:
            attrs[match.group(1)] = match.group(2)
flush()
package = re.search(r'A: package="([^"]+)"', tree)
resolved = set()
for name in named:
    if name.startswith(".") and package:
        name = package.group(1) + name
    if name.startswith(("android.", "java.")):
        continue
    resolved.add(name)
missing_manifest = sorted(n for n in resolved if not present(n))
report["manifestClasses"] = sorted(resolved)
print(f"{len(resolved)} classes named in the manifest; missing from dex: {missing_manifest or 'none'}")
if missing_manifest:
    failures.append(f"classes the manifest names but the dex does not hold: {missing_manifest}")

# ── 2. META-INF/services ─────────────────────────────────────────────────────────────────────
service_report = {}
for service, body in sorted(services.items()):
    impls = [line.split("#")[0].strip() for line in body.splitlines() if line.split("#")[0].strip()]
    missing = [impl for impl in impls if not present(impl)]
    service_report[service] = {"implementations": impls, "missing": missing}
    if missing:
        failures.append(f"{service} names classes the dex does not hold: {missing}")
report["services"] = service_report
print(json.dumps(service_report, indent=1))
coil_services = [s for s in services if "coil3" in s]
if not coil_services:
    failures.append("no coil3 META-INF/services file in the APK — Coil would find no network fetcher")
elif not any("okhttp" in impl.lower() for s in coil_services for impl in service_report[s]["implementations"]):
    failures.append("no Coil service file names the OkHttp network fetcher")

# ── 3. sherpa-onnx native methods against the library's exports ─────────────────────────────


def mangle(text: str) -> str:
    out = []
    for char in text:
        if char in "/.":
            out.append("_")
        elif char == "_":
            out.append("_1")
        elif char == ";":
            out.append("_2")
        elif char == "[":
            out.append("_3")
        elif char.isascii() and char.isalnum():
            out.append(char)
        else:
            out.append("_0%04x" % ord(char))
    return "".join(out)


natives = {}
for desc, info in classes.items():
    if desc.startswith("Lcom/k2fsa/sherpa/onnx/"):
        for method in info["native"]:
            natives[f"Java_{mangle(desc[1:-1])}_{mangle(method)}"] = f"{desc[1:-1].replace('/', '.')}.{method}"
jni_report = {"nativeMethodsInDex": len(natives)}
for lib_name, exported in jni.items():
    java = {s for s in exported if s.startswith("Java_com_k2fsa_sherpa_onnx_")}
    short = {s.split("__")[0] for s in java}
    unbound = sorted(v for k, v in natives.items() if k not in short)
    orphaned = sorted(s for s in short if s not in natives)
    jni_report[lib_name] = {
        "exportedJavaSymbols": len(java),
        "jniOnLoad": "JNI_OnLoad" in exported,
        "dexNativesWithNoSymbol": unbound,
        "symbolsWithNoDexNative": orphaned[:40],
        "symbolsWithNoDexNativeCount": len(orphaned),
    }
    if unbound and "JNI_OnLoad" not in exported:
        failures.append(f"{lib_name}: native methods with no exported symbol: {unbound[:10]}")
report["jni"] = jni_report
print(json.dumps(jni_report, indent=1))
if not natives:
    failures.append("no sherpa-onnx native method survived in the dex at all")

# ── 4. Named entry points and the Retrofit interfaces ────────────────────────────────────────
mapping = (MAPPING_DIR / "mapping.txt").read_text(encoding="utf-8", errors="replace")
renamed = {}
for line in mapping.splitlines():
    match = re.match(r"^(\S+) -> (\S+):$", line)
    if match:
        renamed[match.group(1)] = match.group(2)
must_keep_name = [
    "androidx.credentials.playservices.CredentialProviderPlayServicesImpl",
    "androidx.camera.camera2.Camera2Config$DefaultProvider",
    "com.k2fsa.sherpa.onnx.OfflineRecognizer",
]
retrofit = [
    "com.designprototype.workshop.data.WorkshopRepositoryApi",
    "com.designprototype.workshop.data.UsageApi",
    "com.designprototype.workshop.data.DwReportHistoryApi",
    "com.designprototype.workshop.data.DwAsrModelEndpointApi",
    "com.designprototype.workshop.ui.DwJoinCardApi",
    "com.designprototype.workshop.ui.DwWorkshopJoinApi",
    "com.designprototype.workshop.ui.SttProviderApi",
]
named_report = {}
for name in must_keep_name:
    named_report[name] = {"inDex": present(name), "mappedTo": renamed.get(name)}
    if not present(name):
        failures.append(f"{name} is not in the dex under its own name")
for name in retrofit:
    target = renamed.get(name)
    in_dex = bool(target) and descriptor(target) in classes
    methods = len(classes.get(descriptor(target), {}).get("methods", ())) if target else 0
    named_report[name] = {"mappedTo": target, "inDex": in_dex, "methods": methods}
    if not in_dex or methods == 0:
        failures.append(f"Retrofit interface {name} did not survive with its methods ({target}, {methods})")
serializers = [k for k in renamed if k.startswith("com.designprototype.workshop") and k.endswith("$$serializer")]
renamed_serializers = [k for k in serializers if renamed[k] != k]
named_report["appSerializers"] = {"kept": len(serializers), "renamed": len(renamed_serializers)}
k2fsa = [k for k in renamed if k.startswith("com.k2fsa.sherpa.onnx")]
named_report["k2fsaClasses"] = {"kept": len(k2fsa), "renamed": sum(1 for k in k2fsa if renamed[k] != k)}
report["named"] = named_report
print(json.dumps(named_report, indent=1))
if named_report["k2fsaClasses"]["renamed"]:
    failures.append("R8 renamed sherpa-onnx classes, whose names the native code binds to")

# ── 5. Which libraries' own rules R8 applied ─────────────────────────────────────────────────
configuration = (MAPPING_DIR / "configuration.txt").read_text(encoding="utf-8", errors="replace")
banners = re.findall(r"# The proguard configuration file for the following section is (.+)", configuration)
interesting = ("coil", "retrofit", "okhttp", "serialization", "credentials", "startup", "camera", "mlkit",
               "media3", "firebase", "googleid", "play-services", "kotlinx")
applied = sorted({re.sub(r".*/transformed/|.*/files-2.1/", "", b).strip() for b in banners
                  if any(word in b.lower() for word in interesting)})
report["consumerRules"] = applied
print("consumer rules applied:\n  " + "\n  ".join(applied))

report["failures"] = failures
(OUT / "static-checks.json").write_text(json.dumps(report, indent=2, default=sorted), encoding="utf-8")
summary = os.environ.get("GITHUB_STEP_SUMMARY")
if summary:
    with open(summary, "a", encoding="utf-8") as handle:
        handle.write("### Release APK, read before the emulator\n\n")
        handle.write(f"- signer: {'; '.join(signer)}\n")
        handle.write(f"- manifest-named classes: {len(resolved)}, missing {len(missing_manifest)}\n")
        handle.write(f"- META-INF/services: {', '.join(sorted(services)) or 'none'}\n")
        handle.write(f"- sherpa natives in dex: {len(natives)}; libraries: "
                     + ", ".join(f"{k} exports {v['exportedJavaSymbols']}" for k, v in jni_report.items()
                                 if isinstance(v, dict)) + "\n")
        handle.write(f"- app serializers kept: {named_report['appSerializers']['kept']}\n")
        handle.write(f"- failures: {failures or 'none'}\n\n")
if failures:
    print("FAIL:\n  " + "\n  ".join(failures))
    sys.exit(1)
print("static checks passed")

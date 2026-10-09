#!/usr/bin/env python3
"""Drive the R8-shrunk RELEASE build on an emulator, against stub.py, and fail on what R8 breaks.

TEMPORARY, like the job that runs it. What a shrunk build gets wrong does not fail a compile: it is a
ClassNotFoundException, a SerializationException or a silently empty image on a screen somebody
opens. So this installs the release APK (signed with the SDK's throwaway debug key — never the real
one), launches it cold, and walks the screens that need no production account:

  1. the sign-in screen, cold from the launcher;
  2. a set-password link with the token in the FRAGMENT, cold from a VIEW intent: the check must go
     out as POST /auth/set-password/check, meet this stub's 404, fall back to the GET, and show the
     purpose the GET answered;
  3. a set-password link with the token in the QUERY plus another parameter, from a VIEW intent:
     same fallback, and the refusal sentence for the reason word the GET answered;
  4. a password sign-in: Retrofit, the converter and OkHttp must parse a full camelCase user row;
  5. the designer profile, which loads two pictures through Coil 3: one https (TLS, Certificate
     Transparency) and one from this stub over loopback, which is the request that proves Coil's
     ServiceLoader-registered OkHttp fetcher survived R8. Both must be DRAWN, checked off a
     screenshot, not merely requested.

Then the whole logcat is searched for crashes and for the class of exception R8 causes, and for the
link tokens.
"""

from __future__ import annotations

import json
import os
import re
import shlex
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from pathlib import Path

PKG = "com.designprototype.workshop"
ACTIVITY = f"{PKG}/.MainActivity"
SDK = os.environ.get("ANDROID_HOME") or os.environ["ANDROID_SDK_ROOT"]
ADB = str(Path(SDK) / "platform-tools" / "adb")
HERE = Path(__file__).resolve().parent
OUT = Path(os.environ["SMOKE_OUT"]).resolve()
APK = Path(os.environ["SMOKE_APK"]).resolve()
PORT = int(os.environ.get("STUB_PORT", "8765"))
STUB_LOG = OUT / "stub-requests.jsonl"
LINK_HOST = "https://designer-repository.vercel.app"
EMAIL = os.environ["SMOKE_EMAIL"]
PASSWORD = os.environ["SMOKE_PASSWORD"]
LINK_VALID = os.environ["SMOKE_LINK_VALID"]
LINK_EXPIRED = os.environ["SMOKE_LINK_EXPIRED"]
HTTPS_IMAGE = os.environ["SMOKE_HTTPS_IMAGE"]
HTTPS_IMAGE_RGB = tuple(int(x) for x in os.environ["SMOKE_HTTPS_IMAGE_RGB"].split(","))

INVITE_LINE = "Invitation — for an account that has never had a password."
EXPIRED_LINE = "This link has expired. Ask the administrator for a new one."

R8_SIGNATURES = (
    "ClassNotFoundException",
    "NoClassDefFoundError",
    "NoSuchMethodError",
    "NoSuchFieldError",
    "NoSuchMethodException",
    "NoSuchFieldException",
    "UnsatisfiedLinkError",
    "SerializationException",
    "MissingFieldException",
    "Serializer for class",
    "AbstractMethodError",
    "IncompatibleClassChangeError",
    "ExceptionInInitializerError",
    "VerifyError",
    "Resources$NotFoundException",
    "ServiceConfigurationError",
    "Unable to create converter",
    "Unable to create call adapter",
)

results: list[dict] = []
observations: list[dict] = []
app_pids: set[str] = set()


class Failure(Exception):
    pass


def run(args: list[str], timeout: int = 120, check: bool = True) -> str:
    done = subprocess.run(args, capture_output=True, text=True, timeout=timeout, errors="replace")
    if check and done.returncode != 0:
        raise Failure(f"{' '.join(args)} exited {done.returncode}: {done.stdout[-800:]} {done.stderr[-800:]}")
    return done.stdout


def adb(*args: str, timeout: int = 120, check: bool = True) -> str:
    return run([ADB, *args], timeout=timeout, check=check)


def shell(command: str, timeout: int = 120, check: bool = True) -> str:
    return adb("shell", command, timeout=timeout, check=check)


def record(name: str, ok: bool, detail: str) -> None:
    results.append({"check": name, "ok": ok, "detail": detail})
    print(("PASS " if ok else "FAIL ") + name + " — " + detail, flush=True)


def note_pid() -> None:
    pid = shell(f"pidof {PKG}", check=False).strip()
    for one in pid.split():
        app_pids.add(one)


# ── The screen, as uiautomator sees it ──────────────────────────────────────────────────────────

BOUNDS = re.compile(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]")


def dump(tag: str | None = None) -> list[dict]:
    dumped = False
    for _ in range(6):
        # Removed first, so a dump that fails cannot leave the previous screen behind to be read.
        shell("rm -f /sdcard/window.xml", check=False)
        if "dumped to" in shell("uiautomator dump /sdcard/window.xml", check=False, timeout=60):
            dumped = True
            break
        time.sleep(1)
    if not dumped:
        return []
    xml = adb("exec-out", "cat", "/sdcard/window.xml", check=False)
    if tag:
        (OUT / f"ui-{tag}.xml").write_text(xml, encoding="utf-8")
    nodes: list[dict] = []
    try:
        root = ET.fromstring(xml)
    except ET.ParseError:
        return nodes
    for el in root.iter("node"):
        match = BOUNDS.match(el.get("bounds", ""))
        if not match:
            continue
        x1, y1, x2, y2 = (int(v) for v in match.groups())
        nodes.append({
            "text": el.get("text", ""),
            "desc": el.get("content-desc", ""),
            "hint": el.get("hint", ""),
            "cls": el.get("class", ""),
            "pkg": el.get("package", ""),
            "clickable": el.get("clickable") == "true",
            "checkable": el.get("checkable") == "true",
            "checked": el.get("checked") == "true",
            "enabled": el.get("enabled") == "true",
            "password": el.get("password") == "true",
            "focused": el.get("focused") == "true",
            "bounds": (x1, y1, x2, y2),
        })
    return nodes


def find(nodes: list[dict], text: str | None = None, desc: str | None = None, contains: bool = False) -> list[dict]:
    def hit(value: str, wanted: str) -> bool:
        return wanted in value if contains else value == wanted

    out = []
    for node in nodes:
        if text is not None and (hit(node["text"], text) or hit(node["hint"], text)):
            out.append(node)
        elif desc is not None and hit(node["desc"], desc):
            out.append(node)
    return out


def edit_texts(nodes: list[dict]) -> list[dict]:
    return [n for n in nodes if n["cls"].endswith("EditText") and n["pkg"] == PKG]


def screenshot(tag: str) -> Path:
    path = OUT / f"screen-{tag}.png"
    with open(path, "wb") as handle:
        subprocess.run([ADB, "exec-out", "screencap", "-p"], stdout=handle, timeout=60, check=False)
    return path


def wait_for(what: str, predicate, timeout: float = 45.0, tag: str | None = None):
    deadline = time.time() + timeout
    nodes: list[dict] = []
    while time.time() < deadline:
        nodes = dump()
        found = predicate(nodes)
        if found:
            return found
        time.sleep(1.0)
    dump(f"timeout-{tag or 'wait'}")
    screenshot(f"timeout-{tag or 'wait'}")
    raise Failure(f"timed out after {timeout:.0f}s waiting for {what}")


def tap(node: dict) -> None:
    x1, y1, x2, y2 = node["bounds"]
    shell(f"input tap {(x1 + x2) // 2} {(y1 + y2) // 2}")
    time.sleep(0.7)


def type_text(value: str) -> None:
    shell(f"input text {shlex.quote(value)}")
    time.sleep(0.7)


def ime_shown() -> bool:
    out = shell("dumpsys input_method", check=False)
    return bool(re.search(r"(mInputShown|isInputViewShown|mIsInputViewShown|inputShown)=true", out))


def hide_ime() -> None:
    if ime_shown():
        shell("input keyevent 4")
        time.sleep(1.0)


def disable_imes() -> str:
    """No on-screen keyboard at all, so nothing covers a control the driver is about to tap.

    `input text` injects key events, which a Compose text field takes from a hardware keyboard just
    as well; the sign-in scenario checks that the text arrived and brings the keyboard back if not.
    """
    listed = shell("ime list -s", check=False).split()
    for ime in listed:
        shell(f"ime disable {ime}", check=False)
    return " ".join(listed) or "none listed"


# ── The stub's record of what the app sent ──────────────────────────────────────────────────────

def stub_requests(since: float = 0.0) -> list[dict]:
    if not STUB_LOG.exists():
        return []
    rows = []
    for line in STUB_LOG.read_text(encoding="utf-8").splitlines():
        try:
            row = json.loads(line)
        except json.JSONDecodeError:
            continue
        if row.get("at", 0) >= since:
            rows.append(row)
    return rows


def wait_for_request(what: str, predicate, since: float, timeout: float = 30.0) -> dict:
    deadline = time.time() + timeout
    while time.time() < deadline:
        for row in stub_requests(since):
            if predicate(row):
                return row
        time.sleep(0.5)
    raise Failure(f"the stub never saw {what}")


# ── Pixels ──────────────────────────────────────────────────────────────────────────────────────

def share_near(png: Path, box: tuple[int, int, int, int], rgb: tuple[int, int, int], tolerance: int = 48) -> float:
    from PIL import Image  # installed by the job; only needed here

    image = Image.open(png).convert("RGB")
    x1, y1, x2, y2 = box
    region = image.crop((x1, y1, x2, y2))
    pixels = list(region.getdata())
    if not pixels:
        return 0.0
    close = sum(1 for p in pixels if all(abs(p[i] - rgb[i]) <= tolerance for i in range(3)))
    return close / len(pixels)


# ── The scenarios ───────────────────────────────────────────────────────────────────────────────

def launch_cold_from_launcher(tag: str) -> None:
    shell(f"am force-stop {PKG}")
    time.sleep(1)
    out = shell(f"am start -W -n {ACTIVITY}")
    (OUT / f"am-start-{tag}.txt").write_text(out, encoding="utf-8")
    if "Status: ok" not in out and "Complete" not in out:
        raise Failure(f"am start did not report success: {out.strip()}")
    note_pid()


def open_link(url: str, tag: str) -> None:
    out = shell(f"am start -W -a android.intent.action.VIEW -c android.intent.category.BROWSABLE "
                f"-p {PKG} -d {shlex.quote(url)}")
    (OUT / f"am-start-{tag}.txt").write_text(out.replace(LINK_VALID, "<valid>").replace(LINK_EXPIRED, "<expired>"),
                                             encoding="utf-8")
    note_pid()


def scenario_sign_in_screen() -> None:
    launch_cold_from_launcher("01")
    wait_for(
        "the sign-in card",
        lambda n: find(n, text="Login") and (edit_texts(n) or find(n, text="Email, phone or empanelment number")),
        tag="sign-in",
    )
    screenshot("01-sign-in")
    record("cold launch shows the sign-in card", True, "Login button and identifier box on screen")


def check_fallback(since: float, which: str) -> str:
    post = wait_for_request(
        f"POST /api/auth/set-password/check for the {which} link",
        lambda r: r["method"] == "POST" and r["path"] == "/api/auth/set-password/check" and r.get("link") == which,
        since,
    )
    get = wait_for_request(
        f"the GET fallback for the {which} link",
        lambda r: r["method"] == "GET" and r["path"] == "/api/auth/set-password" and r.get("link") == which,
        since,
    )
    if post["status"] != 404 or post.get("queryHasToken"):
        raise Failure(f"the POST check was not the body-only 404 it should be: {post}")
    if get["at"] < post["at"]:
        raise Failure("the GET went out before the POST")
    if get["status"] != 200:
        raise Failure(f"the GET fallback answered {get['status']}")
    return f"POST check -> 404, then GET -> 200, {get['at'] - post['at']:.2f}s apart; UA {post['userAgent']!r}"


def scenario_link_in_fragment() -> None:
    shell(f"am force-stop {PKG}")
    time.sleep(1)
    since = time.time()
    open_link(f"{LINK_HOST}/set-password#token={LINK_VALID}", "02")
    wait_for("the purpose line the GET answered (INVITE)", lambda n: find(n, text=INVITE_LINE), tag="link-fragment")
    screenshot("02-link-fragment")
    record("set-password link, #token=, cold start", True, check_fallback(since, "valid"))


def scenario_link_in_query() -> None:
    shell("input keyevent 4")
    time.sleep(1.5)
    since = time.time()
    open_link(f"{LINK_HOST}/set-password?token={LINK_EXPIRED}&src=chat", "03")
    wait_for("the refusal sentence for reason=expired", lambda n: find(n, text=EXPIRED_LINE), tag="link-query")
    screenshot("03-link-query")
    record("set-password link, ?token=&src=, warm", True, check_fallback(since, "expired"))


def scenario_password_sign_in() -> None:
    launch_cold_from_launcher("04")
    nodes = wait_for("two text boxes on the sign-in card", lambda n: n if len(edit_texts(n)) >= 2 else None,
                     tag="sign-in-boxes")
    boxes = edit_texts(nodes)
    identifier = next((b for b in boxes if not b["password"]), boxes[0])
    secret = next((b for b in boxes if b["password"]), boxes[1])
    tap(identifier)
    type_text(EMAIL)
    typed = [b["text"] for b in edit_texts(dump()) if not b["password"]]
    if EMAIL not in typed:
        # Key events did not reach the box without a keyboard: bring the keyboard back and type as a
        # person would, hiding it again before anything below it is tapped.
        shell("ime reset", check=False)
        time.sleep(1.5)
        tap(identifier)
        type_text(EMAIL)
        hide_ime()
        typed = [b["text"] for b in edit_texts(dump()) if not b["password"]]
        if EMAIL not in typed:
            raise Failure(f"the identifier did not reach the box (saw {typed!r})")
    tap(secret)
    type_text(PASSWORD)
    hide_ime()
    nodes = dump("sign-in-filled")
    agree = find(nodes, text="I agree to the")
    if not agree:
        raise Failure("no consent row on the sign-in card")
    tap(agree[0])
    nodes = dump("sign-in-agreed")
    if not any(n["checkable"] and n["checked"] for n in nodes):
        raise Failure("the consent row did not tick")
    login = find(nodes, text="Login")
    if not login:
        raise Failure("no Login button")
    if not login[0]["enabled"]:
        raise Failure("the Login button stayed disabled with both boxes filled and the consent ticked")
    since = time.time()
    tap(login[0])

    def landed(n):
        if find(n, text="Skip"):
            return "walkthrough"
        if find(n, desc="Open menu"):
            return "dashboard"
        return None

    where = wait_for("the walkthrough or the dashboard after sign-in", landed, timeout=60, tag="after-login")
    if where == "walkthrough":
        screenshot("04a-walkthrough")
        tap(find(dump(), text="Skip")[0])
        wait_for("the dashboard after skipping the walkthrough", lambda n: find(n, desc="Open menu"), tag="dashboard")
    note_pid()
    login_row = wait_for_request("POST /api/auth/login", lambda r: r["path"] == "/api/auth/login", since)
    if login_row["status"] != 200:
        raise Failure(f"the stub refused the sign-in: {login_row}")
    authed = wait_for_request("an authenticated request after sign-in", lambda r: r.get("bearer"), since)
    nodes = dump("dashboard")
    screenshot("04-dashboard")
    stats_on_screen = bool(find(nodes, text="4,217", contains=True) or find(nodes, text="4217", contains=True))
    record(
        "password sign-in reaches the signed-in app",
        True,
        f"login 200, then {authed['method']} {authed['path']} carried the session; landed on {where}; "
        f"dashboard total 4,217 {'on screen' if stats_on_screen else 'not on the first screen'}",
    )


def scenario_designer_profile_pictures() -> None:
    nodes = dump()
    entry = find(nodes, text="My designer profile")
    since = time.time()
    if not entry:
        menu = find(nodes, desc="Open menu")
        if not menu:
            raise Failure("no menu button on the signed-in screen")
        tap(menu[0])
        for _ in range(10):
            nodes = dump()
            entry = find(nodes, text="My designer profile")
            if entry:
                break
            shell("input swipe 300 1900 300 800 400")
            time.sleep(1.0)
        dump("menu")
        screenshot("05a-menu")
    if not entry:
        raise Failure("no 'My designer profile' entry on the dashboard or in the menu")
    tap(entry[0])
    wait_for_request("GET /api/designers/me/profile", lambda r: r["path"] == "/api/designers/me/profile", since)
    fetched = wait_for_request(
        "Coil fetching the loopback picture",
        lambda r: r["path"] == "/smoke-media/signature.png" and r["method"] == "GET",
        since,
        timeout=40,
    )
    for media in ("cmsmokephoto", "cmsmokesignature"):
        row = wait_for_request(f"GET /api/media/{media}", lambda r, m=media: r["path"] == f"/api/media/{m}", since)
        if row["status"] != 200:
            raise Failure(f"/api/media/{media} answered {row['status']}")

    # Bring both pictures on screen, then read them off a screenshot.
    wanted = {"Photograph": None, "Signature": None}
    for attempt in range(16):
        nodes = dump()
        size = shell("wm size").strip().split()[-1]
        height = int(size.split("x")[1])
        for label in wanted:
            hits = [n for n in find(nodes, desc=label) if n["bounds"][1] >= 0 and n["bounds"][3] <= height - 150
                    and n["bounds"][2] > n["bounds"][0]]
            wanted[label] = hits[0] if hits else None
        if all(wanted.values()):
            break
        shell(f"input swipe 540 {int(height * 0.75)} 540 {int(height * 0.35)} 400")
        time.sleep(1.2)
    dump("profile")
    if not all(wanted.values()):
        screenshot("05-profile-missing")
        raise Failure(f"the picture slots never came on screen: {[k for k, v in wanted.items() if not v]}")
    time.sleep(2.0)
    shot = screenshot("05-profile")
    https_share = share_near(shot, wanted["Photograph"]["bounds"], HTTPS_IMAGE_RGB)
    loopback_share = share_near(shot, wanted["Signature"]["bounds"], (255, 0, 255))
    detail = (
        f"https picture {https_share:.0%} of its box in the image's own green; loopback picture "
        f"{loopback_share:.0%} magenta; Coil's request UA {fetched['userAgent']!r}"
    )
    if https_share < 0.08 or loopback_share < 0.40:
        raise Failure("a picture was requested but not drawn: " + detail)
    record("designer profile draws an https picture and a loopback picture through Coil 3", True, detail)


def ime_frame() -> tuple[int, int, int, int] | None:
    """The keyboard's frame while it is shown, from the window manager's own inset sources."""
    out = shell("dumpsys window", check=False, timeout=60)
    (OUT / "dumpsys-window-keyboard.txt").write_text(out, encoding="utf-8")
    for line in out.splitlines():
        if "type=ime" in line and "visible=true" in line:
            match = re.search(r"frame=\[(\d+),(\d+)\]\[(\d+),(\d+)\]", line)
            if match:
                return tuple(int(v) for v in match.groups())
    return None


def observe_keyboard_over_a_long_form() -> None:
    """NOT A CHECK — an observation for the review, which asks whether any form loses its focused box
    under the keyboard now that the app is drawn edge to edge and `SystemBarsInsetsRoot` leaves the
    keyboard's inset alone. The designer profile is the longest form reachable here: bring its last
    text box up, focus it with the keyboard ON, and measure the box against the keyboard's frame."""
    shell("ime reset", check=False)
    time.sleep(1.5)
    size = shell("wm size").strip().split()[-1]
    width, height = (int(v) for v in size.split("x"))
    for _ in range(14):
        shell(f"input swipe {width // 2} {int(height * 0.8)} {width // 2} {int(height * 0.25)} 300")
        time.sleep(0.6)
    nodes = dump("keyboard-before")
    boxes = [n for n in edit_texts(nodes) if n["bounds"][3] <= height - 150 and n["bounds"][1] >= 100]
    if not boxes:
        observations.append({"observation": "keyboard over a long form", "detail": "no text box on screen to focus"})
        return
    target = max(boxes, key=lambda n: n["bounds"][3])
    tap(target)
    time.sleep(3.0)
    frame = ime_frame()
    nodes = dump("keyboard-after")
    screenshot("06-keyboard-on-profile")
    focused = [n for n in edit_texts(nodes) if n["focused"]]
    shown = ime_shown()
    if not focused:
        detail = f"no focused box after the tap (keyboard shown: {shown}, frame {frame})"
    elif frame is None:
        detail = f"keyboard shown: {shown}; its frame could not be read; focused box at {focused[0]['bounds']}"
    else:
        box = focused[0]["bounds"]
        covered = box[3] > frame[1]
        detail = (f"focused box {box}, keyboard top at y={frame[1]} on a {width}x{height} screen: the box is "
                  f"{'COVERED by' if covered else 'above'} the keyboard (it was at {target['bounds']} before)")
    observations.append({"observation": "keyboard over the designer profile's lowest text box", "detail": detail})
    print("OBSERVE " + detail, flush=True)
    shell("input keyevent 4")
    time.sleep(1.0)


def logcat_checks() -> None:
    log = adb("logcat", "-d", "-v", "threadtime", timeout=120, check=False)
    (OUT / "logcat.txt").write_text(log, encoding="utf-8")
    lines = log.splitlines()
    fatal = []
    for index, line in enumerate(lines):
        if "FATAL EXCEPTION" in line:
            window = "\n".join(lines[index:index + 3])
            if PKG in window:
                fatal.append("\n".join(lines[index:index + 25]))
        if f"ANR in {PKG}" in line:
            fatal.append(line)
    record("no FATAL EXCEPTION or ANR in the app", not fatal, f"{len(fatal)} found" + (": " + fatal[0][:1500] if fatal else ""))

    def from_app(line: str) -> bool:
        parts = line.split()
        return len(parts) > 3 and parts[2] in app_pids

    r8 = [line for line in lines if (from_app(line) or PKG in line) and any(sig in line for sig in R8_SIGNATURES)]
    record("no R8-shaped exception logged by or about the app", not r8,
           f"{len(r8)} line(s)" + (": " + " | ".join(r8[:8])[:2000] if r8 else f"; app pids seen {sorted(app_pids)}"))

    tokens = [(line, from_app(line)) for line in lines if LINK_VALID in line or LINK_EXPIRED in line]
    in_app = [line for line, mine in tokens if mine]
    record("no set-password token in the app's own log lines", not in_app,
           f"{len(in_app)} app line(s), {len(tokens) - len(in_app)} system line(s) carry a token"
           + (": " + in_app[0][:300] if in_app else ""))


def main() -> int:
    OUT.mkdir(parents=True, exist_ok=True)
    stub = subprocess.Popen([sys.executable, str(HERE / "stub.py")],
                            stdout=open(OUT / "stub-stdout.txt", "w"), stderr=subprocess.STDOUT,
                            env={**os.environ, "STUB_LOG": str(STUB_LOG), "STUB_PORT": str(PORT)})
    try:
        for _ in range(40):
            try:
                run(["curl", "-fsS", f"http://127.0.0.1:{PORT}/healthz"], timeout=5)
                break
            except Exception:
                time.sleep(0.25)
        adb("wait-for-device")
        adb("reverse", f"tcp:{PORT}", f"tcp:{PORT}")
        adb("logcat", "-G", "16M", check=False)
        print("keyboards disabled: " + disable_imes(), flush=True)
        print(adb("install", "-r", "-g", str(APK), timeout=600), flush=True)
        dumpsys = shell(f"dumpsys package {PKG}")
        version = re.search(r"versionCode=(\d+).*?targetSdk=(\d+)", dumpsys, re.S)
        record("release APK installed", True,
               f"versionCode {version.group(1)}, targetSdk {version.group(2)}" if version else "installed")
        adb("logcat", "-c", check=False)

        for scenario in (scenario_sign_in_screen, scenario_link_in_fragment, scenario_link_in_query,
                         scenario_password_sign_in, scenario_designer_profile_pictures):
            try:
                scenario()
            except Failure as failure:
                record(scenario.__name__, False, str(failure))
            except Exception as error:  # a driver bug is still a red run, with its reason
                record(scenario.__name__, False, f"driver error: {error!r}")
            note_pid()
        try:
            observe_keyboard_over_a_long_form()
        except Exception as error:  # an observation never turns the run red
            observations.append({"observation": "keyboard over a long form", "detail": f"not taken: {error!r}"})
        note_pid()
        logcat_checks()
    finally:
        stub.terminate()
        try:
            stub.wait(timeout=10)
        except subprocess.TimeoutExpired:
            stub.kill()

    unhandled = sorted({f"{r['method']} {r['path']}" for r in stub_requests() if r.get("unhandled")})
    summary = ["### Release build (R8) on the API 37 emulator", "", "| check | result | detail |", "|---|---|---|"]
    for row in results:
        summary.append(f"| {row['check']} | {'pass' if row['ok'] else '**FAIL**'} | {row['detail'].replace('|', '/')} |")
    for row in observations:
        summary.append(f"| {row['observation']} | observed | {row['detail'].replace('|', '/')} |")
    summary += ["", f"Requests the stub answered 404 because it does not model them: {', '.join(unhandled) or 'none'}"]
    text = "\n".join(summary) + "\n"
    (OUT / "summary.md").write_text(text, encoding="utf-8")
    (OUT / "results.json").write_text(json.dumps({"results": results, "observations": observations}, indent=2),
                                      encoding="utf-8")
    if os.environ.get("GITHUB_STEP_SUMMARY"):
        with open(os.environ["GITHUB_STEP_SUMMARY"], "a", encoding="utf-8") as handle:
            handle.write(text)
    print(text, flush=True)
    return 0 if results and all(r["ok"] for r in results) else 1


if __name__ == "__main__":
    sys.exit(main())

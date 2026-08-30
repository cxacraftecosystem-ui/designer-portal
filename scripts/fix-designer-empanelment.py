"""
Diagnose and fix a designer who is allow-listed but refused with
"Your designer access has been suspended."

WHY THIS HAPPENS
----------------
There are two independent gates on sign-in:

  * AccessRoster   - the platform allow-list. Gates everybody.
  * DesignerRoster - the EMPANELMENT list. Gates only accounts whose role is
                     DESIGNER, and it is the one printing this message.

Passing the first does not pass the second. assert_roster_admits()
(app/api/routes/auth.py) refuses a DESIGNER with no ACTIVE DesignerRoster row,
and roster_allows() is literally:

    db.designerroster.find_first(where={"email": address, "isActive": True})

THE SUBTLE CAUSE THIS SCRIPT EXISTS TO CATCH
--------------------------------------------
normalise_email() is only `.strip().lower()`. It does NOT canonicalise Gmail
dots or +aliases. So `sandy.craft3@gmail.com` and `sandycraft3@gmail.com` are
the same mailbox to Google and two different rows here. An admin reading the
roster screen sees the right person and cannot see why the gate refuses them.

This script compares Gmail-canonical forms (dots stripped, +suffix removed) so
a near-miss is reported as a near-miss instead of "not on the roster".

USAGE
-----
    python fix-designer-empanelment.py                       # diagnose only
    python fix-designer-empanelment.py --fix                 # also repair
    python fix-designer-empanelment.py --email someone@x.com

Nothing is written unless --fix is passed.
"""

import argparse
import getpass
import json
import sys
import urllib.error
import urllib.request

DEFAULT_EMAIL = "sandycraft3@gmail.com"


def canonical_gmail(addr: str) -> str:
    """The mailbox Google actually delivers to, for comparison only."""
    addr = (addr or "").strip().lower()
    if "@" not in addr:
        return addr
    local, _, domain = addr.partition("@")
    local = local.split("+", 1)[0]
    if domain in ("gmail.com", "googlemail.com"):
        local = local.replace(".", "")
        domain = "gmail.com"
    return local + "@" + domain


def call(base, method, path, token=None, body=None):
    url = base.rstrip("/") + path
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            raw = r.read().decode("utf-8", "replace")
            return r.status, (json.loads(raw) if raw.strip() else None)
    except urllib.error.HTTPError as e:
        raw = e.read().decode("utf-8", "replace")
        try:
            return e.code, json.loads(raw)
        except ValueError:
            return e.code, {"detail": raw[:400]}
    except Exception as e:
        return 0, {"detail": str(e)}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--email", default=DEFAULT_EMAIL)
    ap.add_argument("--api", help="API base, e.g. https://api.example.com/api")
    ap.add_argument("--fix", action="store_true", help="actually repair the roster")
    args = ap.parse_args()

    target = (args.email or "").strip().lower()
    print("Target: %s" % target)
    print("Gmail-canonical: %s\n" % canonical_gmail(target))

    base = args.api or input("API base URL (e.g. https://.../api): ").strip()
    admin_email = input("Admin email: ").strip()
    admin_pw = getpass.getpass("Admin password: ")

    st, body = call(base, "POST", "/auth/login",
                    body={"email": admin_email, "password": admin_pw})
    if st != 200 or not isinstance(body, dict) or not body.get("accessToken"):
        print("!! Login failed (%s): %s" % (st, (body or {}).get("detail")))
        return 1
    token = body["accessToken"]
    print("Signed in.\n")

    # The roster endpoint gates READ as well as write, so this needs Admin+.
    st, body = call(base, "GET", "/designers/roster?pageSize=500", token=token)
    if st != 200:
        print("!! Could not read the roster (%s): %s" % (st, (body or {}).get("detail")))
        if st == 403:
            print("   Managing the designer roster requires Admin access or above.")
        return 1

    rows = body.get("items") if isinstance(body, dict) else body
    rows = rows or []
    print("Roster rows: %d\n" % len(rows))

    exact = [r for r in rows if (r.get("email") or "").strip().lower() == target]
    near = [r for r in rows
            if r not in exact
            and canonical_gmail(r.get("email")) == canonical_gmail(target)]

    if exact:
        r = exact[0]
        print("EXACT MATCH: %s  active=%s  id=%s" % (r.get("email"), r.get("isActive"), r.get("id")))
        if r.get("isActive"):
            print("\nThe row is ACTIVE, so the empanelment gate is NOT what is refusing them.")
            print("Check instead:")
            print("  * the account's role really is DESIGNER (other roles skip this gate)")
            print("  * the platform allow-list (AccessRoster) via /admin/access")
            print("  * that the Google account signs in with EXACTLY this address")
            return 0
        print("\nCAUSE: the row exists but is SUSPENDED (isActive = false).")
        if not args.fix:
            print("Re-run with --fix to restore it, or click Restore on /admin/designers.")
            return 0
        st, body = call(base, "PATCH", "/designers/roster/%s" % r["id"],
                        token=token, body={"isActive": True})
        print(("RESTORED." if st == 200 else "!! Restore failed (%s): %s"
               % (st, (body or {}).get("detail"))))
        return 0 if st == 200 else 1

    if near:
        print("!! NO EXACT ROW, BUT A NEAR MATCH EXISTS - this is almost certainly the cause.")
        for r in near:
            print("   roster has : %r  (active=%s, id=%s)" % (r.get("email"), r.get("isActive"), r.get("id")))
        print("   they sign in as: %r" % target)
        print("\n   Same Gmail mailbox, different strings. normalise_email() only lowercases and")
        print("   trims - it does not strip Gmail dots or +aliases - so the gate cannot match them.")
        print("\n   FIX: correct the roster row's email to the exact address Google returns,")
        print("        or add a second row for it. On /admin/designers, edit the row above.")
        if args.fix:
            rid = near[0]["id"]
            st, body = call(base, "PATCH", "/designers/roster/%s" % rid,
                            token=token, body={"email": target, "isActive": True})
            print("\n" + ("CORRECTED to %s and activated." % target if st == 200
                          else "!! Update failed (%s): %s" % (st, (body or {}).get("detail"))))
        return 0

    print("NO ROW AT ALL for %s." % target)
    print("They are on the platform allow-list but were never empanelled as a designer.")
    if not args.fix:
        print("\nRe-run with --fix to empanel them, or use the form on /admin/designers.")
        return 0
    st, body = call(base, "POST", "/designers/roster", token=token,
                    body={"email": target, "isActive": True,
                          "notes": "Empanelled to resolve sign-in refusal."})
    if st in (200, 201):
        print("EMPANELLED. They can sign in now - no redeploy needed.")
        return 0
    print("!! Failed (%s): %s" % (st, (body or {}).get("detail")))
    return 1


if __name__ == "__main__":
    sys.exit(main())

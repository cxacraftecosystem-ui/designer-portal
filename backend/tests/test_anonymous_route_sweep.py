"""EVERY ROUTE IN THE APPLICATION, ASKED ANONYMOUSLY, AND THE EIGHT THAT ARE ALLOWED TO ANSWER.

**THIS CLASS OF DEFECT HAS SHIPPED IN THIS REPOSITORY TWICE, AND BOTH TIMES IT LOOKED FINE ON THE
SCREEN.** The shape is always "a UI guard over an open endpoint": the web client hides the button and
the phone hides the menu item, so nobody with a browser can reach the route by accident and nobody
reviewing the diff notices that the handler itself asks nothing. Every existing test for such a route
signs in first — that is what a test of the feature looks like — so the whole suite goes green while
``curl`` with no Authorization header does the thing. The defect is not in any one handler; it is
that nothing anywhere was asking the question of ALL of them at once. This module asks it.

**IT READS THE OPENAPI DOCUMENT, NOT ``app.routes``**, for the reason
``tests/test_auth_route_surface.py`` gives: ``app.routes`` on this FastAPI version holds seven
entries, one of which is a lazy ``_IncludedRouter`` standing in for the entire ``/api`` tree, so a
test walking that list concludes this application has no endpoints and passes for ever while checking
nothing. It then goes further than that module and actually ISSUES each request, because "the route
declares a dependency" and "the route refuses a stranger" are different claims and only the second
one is the property anybody cares about.

**A NEW ROUTE FAILS THIS BY NAME OR THE MODULE IS POINTLESS.** Every refusal is collected and
reported together, as ``METHOD /path -> status``, rather than the first one aborting the run: a
reviewer looking at a red build has to be able to see WHICH endpoints are open, and a sweep that
stops at the first is a sweep somebody fixes one route at a time over three days.

── THE ALLOWLIST IS THE POINT, AND IT IS A LIST OF DECISIONS ─────────────────────────────────────

Eight endpoints in this API deliberately answer a caller with no credentials, plus the two health
probes. Each is named below WITH THE REASON, because the only failure mode of a test like this is an
allowlist that grows: the day somebody's new endpoint fails this test, the cheapest way to make the
build green is to add a line here, and a line here has to be a sentence somebody would defend out
loud rather than a path somebody pasted. If you are reading this because you just added an entry:
the question is not "does this need to work without a token", it is "what can a stranger learn or
change by calling it, and is that a thing this institution publishes".

── WHAT THIS DELIBERATELY DOES NOT CATCH, STATED SO NOBODY INFERS COVER IT DOES NOT GIVE ─────────

* **AUTHORISATION, ONLY AUTHENTICATION.** It proves a stranger is refused. It says nothing about
  whether a Crowdsource Volunteer can reach an admin endpoint — that is per-route work and lives in
  the per-feature modules.
* **A ROUTE THAT HIDES FROM THE SCHEMA.** ``include_in_schema=False`` takes a route out of the
  document and therefore out of this sweep. There is exactly one today — the ``HEAD`` arm of
  ``asr_models.download_asr_model_file``, stacked on a ``GET`` that IS swept and shares its
  dependency — and the next one is a blind spot. Grep for the flag before trusting this file.
* **THE 200 SIDE OF THE ALLOWLIST.** ``test_the_allowlisted_endpoints_answer_anonymously`` probes one
  of them to prove the harness reaches handlers at all; the rest are not exercised here, because
  several of them (the APK redirect, the census) do real work and belong to their own modules.

Postgres is required, not because any assertion needs it but because ``TestClient`` runs the app's
lifespan and this suite refuses to open a connection to a database it may not write to. Nothing here
writes a row: every request under test is refused before it reaches a handler.

    docker compose up -d postgres minio
    cd backend && .venv/Scripts/python.exe -m prisma migrate deploy --schema prisma/schema.prisma
"""

import re
from typing import Any

import pytest
from conftest import needs_db

import app.services.stage_definitions  # noqa: F401  - installs the registry the router imports
from app.main import app as application

pytestmark = [needs_db]

#: The statuses a stranger may be answered with. 403 as well as 401 because ``_user_from_bearer``
#: answers 403 for a token whose SCOPE is wrong, and a route could legitimately be reached that way;
#: 422 is deliberately NOT here. FastAPI solves a path operation's dependencies before it validates
#: the endpoint's own body and query parameters, so an auth dependency raises before a malformed body
#: can, and a 422 from an un-authenticated probe means the handler was reached.
REFUSALS = frozenset({401, 403})

#: The HTTP methods an OpenAPI path item can carry. Anything else in that dict (``parameters``,
#: ``summary``) is not an operation and must not be probed.
METHODS = ("get", "put", "post", "delete", "patch", "head", "options", "trace")

#: A stand-in for every ``{path_param}``. Nothing resolves it — the point is that the refusal happens
#: before anything tries — and it is spelled distinctively so that if one ever DOES reach a handler
#: and get logged, the line says where it came from.
PROBE_SEGMENT = "anonymous-route-sweep"

#: ``(METHOD, path)`` -> why this one is allowed to answer a caller with no credentials.
#:
#: READ THE MODULE DOCSTRING BEFORE ADDING A LINE. Paths are the OpenAPI ones, verbatim, so that
#: renaming or removing a public endpoint fails ``test_the_allowlist_names_only_routes_that_exist``
#: rather than silently leaving a permission behind for a path nothing serves.
PUBLIC_ALLOWLIST: dict[tuple[str, str], str] = {
    # The sign-in door itself. It cannot require a credential to accept a credential.
    ("POST", "/api/auth/login"): "the sign-in door: it is what a caller has no token BEFORE.",
    # Answers {"ok": true} and nothing else. The session is a stateless JWT with no server-side row,
    # so there is nothing to invalidate; the clients call it to clear their own storage. Requiring a
    # token would mean an expired session could not sign itself out.
    ("POST", "/api/auth/logout"): "clears the client's own storage; there is no server-side session.",
    # The person holding a set-password link cannot sign in — that is the whole reason they were sent
    # one. Both arms are guarded by the link's own signature, expiry, single-use row and credential
    # fingerprint, and the GET deliberately reports nothing about the ACCOUNT, only about the link.
    ("GET", "/api/auth/set-password"): "a link holder has no session by definition; the token is the credential.",
    ("POST", "/api/auth/set-password"): "same: redeeming the link is how the account gets a password at all.",
    # The machine credential door. Email + password in, a scoped read token out — the second thing in
    # this API that turns a password into a token, and like the first it cannot ask for one.
    ("POST", "/api/datasets/token"): "mints the machine token FROM a password; there is nothing to present.",
    # Counts of public record types and when they were counted. World-readable by design: a census is
    # the same number for everybody, and it is the landing page's first request.
    ("GET", "/api/public/census"): "aggregate counts on a public landing page; nothing is attached to a person.",
    # What a person is agreeing to, read on a sign-in screen ABOVE the credential form. A gate here
    # would mean the only way to see what you are agreeing to is to agree first.
    ("GET", "/api/usage/consent/notice"): "the consent text itself, read before there is any account to gate.",
    # A plain link navigation cannot carry a bearer token, and the object it redirects to is already
    # world-readable — every phone in the field fetches it with no credentials when it self-updates.
    ("GET", "/api/app/download"): "a browser link to an object that is public anyway.",
    # The health family. CloudFront's origin check and the uptime monitor call these on a fixed
    # cadence with no credentials, and a 401 to either reads as an outage.
    ("GET", "/health"): "CloudFront origin check and the uptime monitor; a 401 here reads as an outage.",
    ("GET", "/health/ready"): "the same, plus the database probe alerting is meant to watch.",
}


@pytest.fixture(scope="module")
def client():
    """One client for the whole sweep. ``TestClient`` runs the lifespan, which is why this module
    needs a database at all — nothing below writes a row."""
    from fastapi.testclient import TestClient

    with TestClient(application) as test_client:
        yield test_client


def _operations() -> list[tuple[str, str]]:
    """Every ``(METHOD, path)`` this application publishes, from the OpenAPI document.

    Sorted, so a failure report reads the same way twice and can be diffed between runs.
    """
    document = application.openapi()
    found = [
        (method.upper(), path)
        for path, item in document["paths"].items()
        for method in METHODS
        if method in item
    ]
    assert len(found) > 100, (
        f"the sweep found only {len(found)} operations, which is not this application: the OpenAPI "
        "document is not being generated (see test_auth_route_surface for the app.routes trap) and "
        "this module would pass while checking almost nothing"
    )
    return sorted(found)


def _probe(client: Any, method: str, path: str) -> int:
    """Issue one request with NO Authorization header and return the status.

    No body and no query string on purpose. A 422 for a missing body would mean the handler's own
    parameters were validated, which happens only AFTER the path operation's dependencies have been
    solved — so on a properly gated route it is unreachable, and where it appears it is evidence.
    """
    filled = re.sub(r"\{[^}]+\}", PROBE_SEGMENT, path)
    return client.request(method, filled).status_code


def test_no_route_outside_the_allowlist_answers_a_stranger(client):
    """**THE SWEEP.** Every published operation, asked with no credentials.

    The whole failure set is reported at once, by name and with the status each one answered, because
    "some endpoint in this application is open" is not a finding anybody can act on and "these three
    are" is.
    """
    open_doors: list[str] = []
    for method, path in _operations():
        if (method, path) in PUBLIC_ALLOWLIST:
            continue
        status_code = _probe(client, method, path)
        if status_code not in REFUSALS:
            open_doors.append(f"{method} {path} -> {status_code}")

    assert not open_doors, (
        "these operations answered a caller with NO Authorization header instead of refusing:\n  "
        + "\n  ".join(open_doors)
        + "\n\nEither the route is missing an auth dependency — this repository has shipped 'a UI "
        "guard over an open endpoint' twice, and the client hiding the button is not a guard — or "
        "it is genuinely public, in which case add it to PUBLIC_ALLOWLIST in this file WITH THE "
        "SENTENCE that justifies it. A 422 in this list means the handler's own parameters were "
        "reached, which a gated route cannot do."
    )


def test_the_allowlist_names_only_routes_that_exist(client):
    """A PERMISSION MUST NOT OUTLIVE THE ROUTE IT WAS GRANTED FOR.

    An entry for a path nothing serves is dead text that reads as a decision, and the next person to
    add a public endpoint will reasonably copy the nearest line rather than write the sentence. Worse:
    if a public route is ever RENAMED, the stale entry keeps the old path exempt while the new path
    quietly joins the sweep — and if the rename went the other way, the exemption silently covers a
    route nobody re-argued.
    """
    published = set(_operations())
    stale = sorted(
        f"{method} {path}" for method, path in PUBLIC_ALLOWLIST if (method, path) not in published
    )
    assert not stale, (
        "PUBLIC_ALLOWLIST exempts operations this application no longer publishes:\n  "
        + "\n  ".join(stale)
        + "\n\nIf the endpoint moved, move the entry and re-read its sentence; if it is gone, delete "
        "the entry rather than leaving an exemption nobody can trace to a decision."
    )


def test_the_allowlisted_endpoints_answer_anonymously(client):
    """**THE TRIPWIRE'S OWN TRIPWIRE.** Prove an ungated route really does answer this harness.

    Without this, every assertion above is satisfied by a sweep that cannot reach a handler at all —
    a client that 401s on everything for some reason of its own would report a perfectly guarded
    application, for ever, including on the day somebody ships an open endpoint. One allowlisted
    route is probed for real and asserted NOT to be a refusal.

    ``GET /api/usage/consent/notice`` is the one chosen because it is pure: it reads a constant out
    of the usage policy and touches no database, no object store and no external service, so it
    cannot fail for a reason that has nothing to do with what this test is asking.
    """
    status_code = _probe(client, "GET", "/api/usage/consent/notice")
    assert status_code == 200, (
        "the deliberately public consent notice did not answer a caller with no credentials, so "
        "this harness cannot distinguish an open endpoint from a closed one and the sweep above "
        f"proves nothing: got {status_code}"
    )

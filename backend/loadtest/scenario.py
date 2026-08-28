"""THE TRAFFIC MIX. This is the file to argue with; `drive.py` only executes what is written here.

Spec §52 asks for a realistic scenario at ~1,000 concurrently active users and says explicitly that
simulating ONE endpoint is not acceptable. So the mix below is ten named steps, and each one is a
call a real client actually makes, taken from the web app and the Android client rather than
invented for the benchmark.

WHY THE MIX IS SEPARATE FROM THE ENGINE. A load result is only worth the scenario behind it, and a
scenario buried inside a driver is a scenario nobody reads. Everything a reviewer needs to disagree
with — which endpoints, in what proportion, with what body, how often a user signs in — is here, in
one screen, with the reason beside each weight.

────────────────────────────────────────────────────────────────────────────────────────────────
WHERE THE WEIGHTS COME FROM, AND WHY SIGN-IN IS NOT ONE-TENTH OF THE TRAFFIC
────────────────────────────────────────────────────────────────────────────────────────────────

The naive reading of "model these ten things" is ten equal tenths. That would be wrong in the one
direction that matters most: it would make sign-in 10% of all requests, and sign-in is the single
most expensive call this API serves (bcrypt, ~370 ms of BLOCKING CPU — see the README). An equal
mix would therefore measure bcrypt and nothing else, and would report a bottleneck that a real
population does not experience, because a real user signs in ONCE and then makes hundreds of
requests on the token.

So the weights model a session, not a menu:

  * Every identity signs in exactly once during warm-up. That cost is measured and reported on its
    own, as a throughput ceiling, because it is one — it is just not paid on every request.
  * SIGN_IN keeps a 1% slice in the steady-state mix, which stands for new sessions arriving while
    the others are working. At 1,000 users with a ~1 s think time that is ~10 sign-ins a second,
    which is already several times what one worker can absorb; see the README's finding on it.
  * The read:write ratio is ~4:1, which is what a capture app looks like — a designer opens a
    workshop, reads three screens, types into one.

CONTROL is not part of the product's traffic at all. It is `/health`, which touches nothing: no
auth, no database, no serialisation. It rides along at a small weight as the driver's own
instrument. If CONTROL's p95 climbs with concurrency, the BOX is saturated (or the driver is) and
every other number in that run is measuring the harness, not the API. A load test with no control
line cannot tell those two apart, and this one is being run on a shared developer machine where
that distinction is not academic.
"""

from collections.abc import Callable
from dataclasses import dataclass, field
from typing import Any

# Stage keys and entity/field names below are not invented — they are read out of the registry the
# API itself serves at GET /api/design-workshops/schema, and `drive.py` re-derives them at warm-up
# so a registry change cannot leave this file quietly sending fields the server drops. These are the
# fallbacks used when discovery is unavailable.
SETUP_STAGE = "WORKSHOP_SETUP"
SETUP_ENTITY = "workshopSetup"
PARTICIPANT_STAGE = "WORKSHOP_PLAN_PARTICIPANTS_OPENING"
PARTICIPANT_ENTITY = "participant"


@dataclass(frozen=True)
class Step:
    """One call a client makes, plus everything needed to issue it and to read the result.

    ``build`` receives the per-identity session state (token, workshop id, a counter) and returns
    ``(method, path, json_body_or_None)``. It is a function rather than a template string because
    half of these steps need a value that only exists at run time — the workshop the identity was
    given, a unique participant name, a page number that varies so the database cannot serve every
    caller from one cached plan.

    ``expect`` is the set of status codes that mean "the server did its job". It is per step and
    not a global "< 400" because two of these steps have a legitimate non-200 answer: the schema
    fetch answers 304 to a client that already holds it (that IS the success path, and counting it
    as an error would hide the conditional-GET win), and the sign-in step can legitimately meet the
    credential limiter's 429 when the limiter is enabled — which is a result, not a failure of the
    harness.
    """

    name: str
    weight: float
    build: Callable[["Session"], tuple[str, str, Any]]
    expect: frozenset[int] = frozenset({200})
    #: Steps that must not run before warm-up has given the identity a workshop to point at.
    needs_workshop: bool = False


@dataclass
class Session:
    """One simulated person: their own token, their own workshop, their own counters.

    ONE TOKEN PER PERSON IS THE WHOLE POINT, not a detail. The rate limiter that landed on
    2026-08-27 keys its bucket on a digest of the bearer token (`app/scale/rate_limit.py::_identity`),
    so a driver that signed in once and shared one token across 1,000 workers would put all 1,000
    into ONE bucket of 120 requests a minute and would spend the entire run measuring the limiter's
    429 path. Every field here exists to keep the simulated population as distinguishable to the
    server as a real one is.
    """

    index: int
    email: str
    token: str = ""
    user_id: str = ""
    workshop_id: str = ""
    #: Monotonic per-session counter, so writes carry distinct values instead of rewriting one row
    #: with the same bytes (which Postgres can optimise in ways a real workload does not enjoy).
    seq: int = 0
    #: The ETag last seen for the field registry, so the schema step exercises the 304 path the way
    #: a warm client does. None until the first fetch.
    schema_etag: str | None = None
    extra: dict[str, Any] = field(default_factory=dict)

    @property
    def auth(self) -> dict[str, str]:
        return {"Authorization": f"Bearer {self.token}"}


# ────────────────────────────────────────────────────────────────────────────────────────────────
# The ten steps of §52, plus the control line.
# ────────────────────────────────────────────────────────────────────────────────────────────────


def _sign_in(s: "Session") -> tuple[str, str, Any]:
    # A REAL sign-in with the real password, so bcrypt actually runs. A deliberately-wrong password
    # would be cheaper to seed but would exercise the credential-failure budget instead of the
    # success path, and would spend the address's 20-failure allowance in the first four seconds.
    return "POST", "/api/auth/login", {"email": s.email, "password": LOAD_PASSWORD}


def _dashboard(s: "Session") -> tuple[str, str, Any]:
    # The web app's landing call. Documented in routes/dashboard.py as the endpoint that used to
    # issue fourteen sequential reads and now issues one gathered wave — which makes it the best
    # single probe for "is the database round trip or the box the constraint", because its cost is
    # almost entirely one wide wave rather than serialisation.
    return "GET", "/api/dashboard/stats", None


def _workshop_list(s: "Session") -> tuple[str, str, Any]:
    # Paged, and the page VARIES with the session counter. A fixed ?page=1 for every caller is the
    # classic way to accidentally benchmark a warm buffer cache instead of the query.
    page = 1 + (s.seq % 5)
    return "GET", f"/api/design-workshops?page={page}&pageSize=20", None


def _workshop_read(s: "Session") -> tuple[str, str, Any]:
    # THE HEAVIEST READ in the mix and the one a designer opens most: one workshop with every stage,
    # every entry, provenance display names resolved and completeness recomputed.
    return "GET", f"/api/design-workshops/{s.workshop_id}", None


def _record_create(s: "Session") -> tuple[str, str, Any]:
    # Adding a participant to a workshop — the app's canonical "create a record". `replaceCollections`
    # is FALSE on purpose: true is the phone's wholesale-replace sync, and using it here would make
    # every create also a delete of everything the previous iteration wrote, which is a different
    # (and much heavier) query shape than the one the web form produces.
    s.seq += 1
    body = {
        "entries": [
            {
                "entityKey": PARTICIPANT_ENTITY,
                "data": {
                    "name": f"Load Participant {s.index}-{s.seq}",
                    "localName": f"लोड {s.index}-{s.seq}",
                },
            }
        ],
        "replaceCollections": False,
    }
    return "PUT", f"/api/design-workshops/{s.workshop_id}/stages/{PARTICIPANT_STAGE}", body


def _questionnaire(s: "Session") -> tuple[str, str, Any]:
    # The artisan questionnaire's field list. A pure read of a small, hot table — in the mix because
    # a benchmark made only of heavy endpoints tells you nothing about whether the CHEAP calls stay
    # cheap while the heavy ones are running, which is the actual user-visible symptom of saturation.
    return "GET", "/api/questionnaire/questions", None


def _task_list(s: "Session") -> tuple[str, str, Any]:
    # "My tasks". withDerived defaults to true and that is what the clients send, so it is what is
    # measured — the derived progress counts are part of this endpoint's real cost.
    return "GET", "/api/tasks?view=assigned&page=1&pageSize=20", None


def _search(s: "Session") -> tuple[str, str, Any]:
    # Five buckets, five counts, five paged reads. docs/SCALABILITY.md records 8.9 s per call against
    # the cross-region production database; the term varies per call so nothing is served twice.
    term = _SEARCH_TERMS[s.seq % len(_SEARCH_TERMS)]
    return "GET", f"/api/search?q={term}&page=1&pageSize=10", None


def _metadata_write(s: "Session") -> tuple[str, str, Any]:
    # The small, frequent write: renaming a workshop / editing its notes. Distinct from the record
    # create above because it is a single-row UPDATE with no transaction and no collection sweep, and
    # the two behave completely differently under contention.
    s.seq += 1
    return "PATCH", f"/api/design-workshops/{s.workshop_id}", {
        "notes": f"load-test pass {s.seq} from identity {s.index}"
    }


def _media_presign(s: "Session") -> tuple[str, str, Any]:
    # THE SIGNED UPLOAD CREATION, and it is in the mix precisely because it is NOT a byte proxy: the
    # API signs a URL and the client PUTs to object storage directly. Measuring it proves that claim
    # (this call should stay flat under load because it touches no database), and it is the step that
    # would expose a regression the day somebody makes the upload path go through the API.
    s.seq += 1
    return "POST", "/api/media/presign", {
        "filename": f"loadtest-{s.index}-{s.seq}.jpg",
        "mimeType": "image/jpeg",
        "mediaType": "IMAGE",
        "sizeBytes": 1_048_576,
    }


def _schema_fetch(s: "Session") -> tuple[str, str, Any]:
    # The field registry: 149,465 bytes of JSON, 22,875 gzipped (MEASURED, routes/design_workshops.py).
    # Every cold client start fetches it. Included because it is the largest body the API serves to a
    # cold client and therefore the biggest single lump of gzip CPU on a single-worker box — and
    # because `drive.py` sends If-None-Match once it holds an ETag, which turns most of these into
    # 304s, so the run measures the revalidation a warm population actually performs.
    return "GET", "/api/design-workshops/schema", None


def _control(s: "Session") -> tuple[str, str, Any]:
    # NOT PRODUCT TRAFFIC. See the module docstring: this is the harness's own instrument.
    return "GET", "/health", None


_SEARCH_TERMS = ("weave", "clay", "loom", "dye", "brass", "cane", "silk", "block")

#: Shared by every seeded identity. Local only — `seed_load_identities.py` refuses to run against a
#: DSN that is not loopback, on the same guard `scripts/seed_test_accounts.py` uses.
LOAD_PASSWORD = "LoadTest123!"

#: The address pattern the seeder writes and the driver signs in as. The `loadtest+` prefix is what
#: makes the population identifiable in a `psql` session and removable in one statement.
EMAIL_PATTERN = "loadtest+{index:05d}@example.org"


#: The steady-state mix. Weights are relative and need not sum to anything.
#:
#: Read the numbers as "out of every 100 requests a signed-in population makes".
MIX: tuple[Step, ...] = (
    # ── Writes: ~19% ────────────────────────────────────────────────────────────────────────────
    Step("record_create", 8, _record_create, frozenset({200, 422}), needs_workshop=True),
    Step("metadata_write", 6, _metadata_write, frozenset({200}), needs_workshop=True),
    Step("media_presign", 5, _media_presign, frozenset({200})),
    # ── Reads: ~78% ─────────────────────────────────────────────────────────────────────────────
    Step("workshop_read", 18, _workshop_read, frozenset({200}), needs_workshop=True),
    Step("dashboard", 14, _dashboard),
    Step("workshop_list", 12, _workshop_list),
    Step("task_list", 10, _task_list),
    Step("questionnaire", 9, _questionnaire),
    Step("search", 8, _search),
    # 304 is the success path for a client that already holds the registry — see the step's comment.
    Step("schema_fetch", 7, _schema_fetch, frozenset({200, 304})),
    # ── New sessions arriving: ~1% ──────────────────────────────────────────────────────────────
    # 429 is expected, not an error, when the run is configured with the limiter on.
    Step("sign_in", 1, _sign_in, frozenset({200, 429})),
    # ── The harness's own control line, not product traffic ─────────────────────────────────────
    Step("control", 4, _control),
)

#: THE SYNC BURST, kept out of MIX on purpose. A returning handset does not interleave one stage
#: write with other traffic — it posts everything it captured while offline, back to back, as fast
#: as the link allows (`android/.../data/WorkshopSync.kt`). That is a different arrival process, so
#: it is a different phase: `drive.py --sync-burst N` gives N identities this list to fire with no
#: think time at all, on top of the steady mix, which is what a morning with returning field devices
#: looks like.
SYNC_BURST: tuple[str, ...] = ("record_create", "record_create", "record_create", "metadata_write")

BY_NAME = {step.name: step for step in MIX}

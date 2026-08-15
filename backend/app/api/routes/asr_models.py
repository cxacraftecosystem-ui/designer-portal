"""Serving the offline speech model to a designer's handset: the manifest, and the bytes.

``app/services/asr_artifacts.py`` carries the whole argument — why this deployment hosts the model at
all, where the bytes live, and what the published digest is and is not. This file is the HTTP surface
and only the decisions that are HTTP decisions are argued here.

================================================================================================
THREE ROUTES, AND WHY THE MANIFEST IS SEPARATE FROM THE BYTES
================================================================================================

``GET /api/asr-models``                                the catalogue, with what this deployment
                                                       actually holds of each artifact
``GET /api/asr-models/{id}``                           one artifact, same shape
``GET|HEAD /api/asr-models/{id}/files/{name}``         the bytes, resumable

**The manifest answers 200 even when nothing is published.** "Which models exist and is this one
here?" is a question this deployment can always answer, and answering it with an error would leave a
phone unable to distinguish "not published" from "your token expired". The BYTES route is the one
that 503s.

================================================================================================
RANGE, AND WHY IT IS ``FileResponse`` AND NOT A HAND-ROLLED STREAM
================================================================================================

The client half is resumable by design: ``DwDownload.kt`` keeps a ``.part`` file, asks for
``Range: bytes=<partial>-``, and — this is the part that decides the shape of the server — REFUSES to
append unless the answer is a **206 whose ``Content-Range`` starts at exactly the offset it asked
for** (``dwRangeHonoured``). A server that ignores ``Range`` answers 200 with the whole file, and
appending that to a partial produces a corrupt bundle, so a server that ignores Range makes the whole
client half pointless.

Starlette's ``FileResponse`` already implements the entire RFC 7233 half of this — 206 with
``Content-Range``, 416 with ``bytes */size`` for an unsatisfiable range, suffix ranges,
``multipart/byteranges`` for several, ``If-Range`` against the validator, ``Accept-Ranges: bytes``,
and a HEAD that sends headers with no body. Re-implementing it here would be a second, worse copy of
tested code; what this module adds is the two things it cannot know:

* **The verification gate in front of it.** Nothing reaches ``FileResponse`` until the file on disk
  has been stat'ed AND hashed in this process and matched against the published digest.
* **``ETag`` = the artifact's SHA-256**, in place of the ``mtime``-and-size hash ``FileResponse``
  derives by default. Two replicas that received the file at different times would otherwise hand out
  different validators for identical bytes, and a client resuming with ``If-Range`` against the other
  replica would be told to start its 365 MB again. The content digest is stable across replicas,
  across re-copies and across a redeploy, and it is a genuinely strong validator: it changes exactly
  when the bytes change.

``Cache-Control: private, no-transform``. ``no-transform`` is the load-bearing half — it forbids an
intermediary from recompressing or otherwise "helpfully" altering a body whose digest is the entire
point. ``private`` keeps a shared cache from storing an authenticated multi-hundred-megabyte body.

================================================================================================
WHAT THIS ROUTE IS DELIBERATELY *NOT* BEHIND
================================================================================================

**Not the daily dictation cap** (``services/dictation_cap.py``) and **not the Tier 3 consent gate**
(``services/dictation_consent.py``). Neither applies and applying either would be a category error:
the cap is a ceiling on provider SPEND and this endpoint spends nothing at a provider, while the
consent gate exists because Tier 3 dictation sends a recording of an artisan's voice off the handset.
The artifact goes the other way — it is a file travelling TO the phone, and the model it delivers runs
on the device and sends nothing anywhere. That is the whole reason a designer wants it. A test asserts
this module imports neither, so the coupling cannot arrive later by habit.

**Not unauthenticated**, unlike ``/app/download``. That one is anonymous for a reason that does not
hold here: a browser cannot attach a bearer token to a plain link navigation, and the APK it redirects
to is world-readable in a bucket anyway. This fetch is made by the app itself, which already holds a
token, so there is nothing to be gained by opening it and the entitlement rule below is free.
"""

from typing import Any

from fastapi import APIRouter, Depends, HTTPException, Request, Response, status
from fastapi.responses import FileResponse, JSONResponse

from app.core.deps import can_run_design_workshops, get_current_user

# THE MODULE, not its members. Every read of the catalogue below goes through this name, so there is
# exactly one place a test — or an operator's debug shell — can substitute a catalogue, and no stale
# copy of the tuple bound into this module at import time.
from app.services import asr_artifacts
from app.services.asr_artifacts import ArtifactVerdict, AsrArtifact, FileVerdict

router = APIRouter(prefix="/asr-models", tags=["asr-models"])

#: The bytes route's name, so the manifest's URLs are built from where the route is actually mounted
#: rather than from a "/api/…" string that would go stale the day the prefix moves.
_FILE_ROUTE = "asr_model_file"


def _require_entitlement(user: Any) -> None:
    """Who may download a speech model: **the same set that may run a design workshop.**

    ``can_run_design_workshops`` — Designer, Admin, Master Admin — reused rather than a new
    predicate, and it is worth being explicit that this is a SET and not a rank threshold, so a
    PROFESSOR is refused despite outranking a designer. That is the existing rule and its own
    docstring argues it; inventing a laxer one here for a file download would make the offline half
    of dictation reachable by accounts the online half is not, which is the kind of split nobody
    would find until it mattered.

    Fails closed: anything this predicate does not admit gets a 403, and an unauthenticated request
    never reaches here at all because ``get_current_user`` has already raised 401.
    """
    if not can_run_design_workshops(user):
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            # NOT "Designer access or above". The predicate is a SET, and a PROFESSOR — who is above
            # a designer on the ladder and was measured getting this 403 — would read "or above" as
            # saying they already qualify. The sentence has to name the accounts, because the rule
            # does not follow the rank order the reader will assume.
            detail=(
                "The offline speech model is part of running a design workshop, so it needs a "
                "Designer, Admin or Master Admin account."
            ),
        )


def _file_url(request: Request, artifact: AsrArtifact, file_name: str) -> str:
    """The path this file is served at — **a path, not an absolute URL, on purpose.**

    An absolute URL would have to be built from the ``Host`` header, which is client-supplied and is
    the classic way a manifest ends up pointing a fleet at somebody else's server. The client has its
    own base URL for this deployment and its own pinned URL for the artifact; what it needs from here
    is the path, and ``url_for`` derives that from the mounted route so it cannot drift from reality.
    """
    return request.url_for(_FILE_ROUTE, artifact_id=artifact.artifact_id, file_name=file_name).path


def _file_payload(request: Request, verdict: FileVerdict) -> dict[str, Any]:
    """One file, as the manifest states it.

    ``bytes`` and ``sha256`` are the values taken off the file on THIS deployment's disk, and they
    are ``null`` when there is no file to take them off. They are never filled in from the catalogue:
    a manifest that answered with the size and digest it WISHED the file had would be describing
    something that is not there, which is the failure this whole endpoint is built to avoid.
    """
    return {
        "fileName": verdict.spec.file_name,
        "url": _file_url(request, verdict.artifact, verdict.spec.file_name),
        "mediaType": verdict.spec.media_type,
        "bytes": verdict.stat.st_size if verdict.ready and verdict.stat else None,
        "sha256": verdict.sha256 if verdict.ready else None,
        "available": verdict.ready,
        "unavailableReason": verdict.refusal.value if verdict.refusal else None,
        "detail": verdict.detail or None,
    }


def _artifact_payload(request: Request, verdict: ArtifactVerdict) -> dict[str, Any]:
    """One artifact: what it is, what it was measured to hear, and whether it is here."""
    artifact = verdict.artifact
    blocked = verdict.first_refusal
    return {
        "artifactId": artifact.artifact_id,
        "version": artifact.version,
        "quantisation": artifact.quantisation,
        "languages": list(artifact.languages),
        "languageNote": artifact.language_note,
        "upstreamVersion": artifact.upstream_version,
        "provenance": artifact.provenance,
        "available": verdict.ready,
        "unavailableReason": blocked.refusal.value if blocked and blocked.refusal else None,
        "detail": blocked.detail if blocked else None,
        "totalBytes": verdict.total_bytes,
        "files": [_file_payload(request, f) for f in verdict.files],
    }


def _no_store(payload: dict[str, Any]) -> JSONResponse:
    """A manifest response that no intermediary may keep.

    ``/app/download`` refuses caching for the same reason and states it the same way: this body is the
    only moving part in an otherwise immutable arrangement. It flips the moment an operator places or
    removes a file, and a proxy holding yesterday's copy would tell a phone the model is absent for as
    long as its TTL — while the bytes sat there.
    """
    return JSONResponse(payload, headers={"Cache-Control": "no-store, max-age=0"})


@router.get("")
async def list_asr_models(
    request: Request, current_user: Any = Depends(get_current_user)
) -> JSONResponse:
    """Every artifact this build knows about, with what this deployment holds of each.

    200 even when nothing is published — see the module docstring. ``digestSource`` is in the body
    rather than only in the documentation because it is the one thing a reader has to know to use the
    field correctly: it is computed from the bytes this endpoint would serve, and it is not a
    signature and not a substitute for the digest the app pins.
    """
    _require_entitlement(current_user)
    verdicts = [
        await asr_artifacts.verify_artifact(artifact)
        for artifact in asr_artifacts.ASR_MODEL_ARTIFACTS
    ]
    return _no_store(
        {
            "artifacts": [_artifact_payload(request, v) for v in verdicts],
            "digestSource": (
                "Every sha256 here is computed from the bytes this endpoint serves, on each change "
                "to the file. It is not a signature, and a client must verify against the digest "
                "compiled into its own build rather than against this one."
            ),
        }
    )


@router.get("/{artifact_id}")
async def read_asr_model(
    artifact_id: str, request: Request, current_user: Any = Depends(get_current_user)
) -> JSONResponse:
    """One artifact. 404 when this build publishes no such id — that is a client bug, not a 503."""
    _require_entitlement(current_user)
    artifact = asr_artifacts.artifact_by_id(artifact_id)
    if artifact is None:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail=f"This deployment publishes no speech model called “{artifact_id}”.",
        )
    return _no_store(_artifact_payload(request, await asr_artifacts.verify_artifact(artifact)))


# Two stacked decorators rather than one ``api_route(methods=["GET", "HEAD"])``, which is otherwise
# equivalent. ``docs/tools/check-docs.mjs`` counts the API surface by matching ``@router.<verb>(``, so
# a route declared through ``api_route`` is invisible to the generated inventory in
# ``docs/REPO_FACTS.md`` — a route that exists and is not counted is exactly the kind of quiet
# inaccuracy that document is generated to prevent. The GET is the one that carries the name.
@router.get("/{artifact_id}/files/{file_name}", name=_FILE_ROUTE)
@router.head("/{artifact_id}/files/{file_name}", name=_FILE_ROUTE, include_in_schema=False)
async def download_asr_model_file(
    artifact_id: str,
    file_name: str,
    current_user: Any = Depends(get_current_user),
) -> Response:
    """The bytes of one file, resumable, or a 503 that says what is wrong. **Never a short 200.**

    GET and HEAD on one handler because they have to answer identically about size and ranges — a
    HEAD that reported a length the GET did not honour would be worse than no HEAD at all — and
    ``FileResponse`` already suppresses the body for HEAD off the ASGI scope.

    **404 for an id or a name this build does not publish; 503 for one it publishes and cannot
    serve.** The distinction is the whole diagnostic value of the response: 404 means the client
    asked for something that does not exist in any deployment, 503 means it asked correctly and this
    deployment has not been given the bytes.

    The file name is never joined onto a path. It is looked up in the catalogue by exact match
    (``AsrArtifact.file``) and the *catalogue's* string is what reaches the filesystem, so a
    traversal attempt cannot get past the lookup — it is simply not a file this artifact has.
    """
    _require_entitlement(current_user)

    artifact = asr_artifacts.artifact_by_id(artifact_id)
    if artifact is None:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail=f"This deployment publishes no speech model called “{artifact_id}”.",
        )
    spec = artifact.file(file_name)
    if spec is None:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail=f"The {artifact_id} speech model has no file called “{file_name}”.",
        )

    verdict = await asr_artifacts.verify_file(artifact, spec)
    if not verdict.ready or verdict.path is None or verdict.stat is None or verdict.sha256 is None:
        raise HTTPException(status_code=status.HTTP_503_SERVICE_UNAVAILABLE, detail=verdict.detail)

    return FileResponse(
        path=verdict.path,
        # THE SAME STAT THE DIGEST WAS TAKEN AGAINST. Passed in rather than left for FileResponse to
        # take its own, so Content-Length, the range arithmetic and the verified digest all describe
        # one reading of the file instead of three.
        stat_result=verdict.stat,
        media_type=spec.media_type,
        filename=spec.file_name,
        headers={
            # Set before FileResponse's own `setdefault`, so this wins. See the module docstring.
            "etag": f'"{verdict.sha256}"',
            "cache-control": "private, no-transform",
        },
    )

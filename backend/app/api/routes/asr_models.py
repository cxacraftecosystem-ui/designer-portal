from typing import Any

from fastapi import APIRouter, Depends, HTTPException, Request, Response, status
from fastapi.responses import FileResponse, JSONResponse

from app.core.deps import can_run_design_workshops, get_current_user

from app.services import asr_artifacts
from app.services.asr_artifacts import ArtifactVerdict, AsrArtifact, FileVerdict

router = APIRouter(prefix="/asr-models", tags=["asr-models"])

_FILE_ROUTE = "asr_model_file"


def _require_entitlement(user: Any) -> None:
    if not can_run_design_workshops(user):
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail=(
                "The offline speech model is part of running a design workshop, so it needs a "
                "Designer, Admin or Master Admin account."
            ),
        )


def _file_url(request: Request, artifact: AsrArtifact, file_name: str) -> str:
    return request.url_for(_FILE_ROUTE, artifact_id=artifact.artifact_id, file_name=file_name).path


def _file_payload(request: Request, verdict: FileVerdict) -> dict[str, Any]:
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
    return JSONResponse(payload, headers={"Cache-Control": "no-store, max-age=0"})


@router.get("")
async def list_asr_models(
    request: Request, current_user: Any = Depends(get_current_user)
) -> JSONResponse:
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
    _require_entitlement(current_user)
    artifact = asr_artifacts.artifact_by_id(artifact_id)
    if artifact is None:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail=f"There is no speech model called “{artifact_id}”.",
        )
    return _no_store(_artifact_payload(request, await asr_artifacts.verify_artifact(artifact)))


@router.get("/{artifact_id}/files/{file_name}", name=_FILE_ROUTE)
@router.head("/{artifact_id}/files/{file_name}", name=_FILE_ROUTE, include_in_schema=False)
async def download_asr_model_file(
    artifact_id: str,
    file_name: str,
    current_user: Any = Depends(get_current_user),
) -> Response:
    _require_entitlement(current_user)

    artifact = asr_artifacts.artifact_by_id(artifact_id)
    if artifact is None:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail=f"There is no speech model called “{artifact_id}”.",
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
        stat_result=verdict.stat,
        media_type=spec.media_type,
        filename=spec.file_name,
        headers={
            "etag": f'"{verdict.sha256}"',
            "cache-control": "private, no-transform",
        },
    )

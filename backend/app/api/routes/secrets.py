import logging
from typing import Any

from fastapi import APIRouter, Depends, HTTPException, status

from app.core.deps import require_master_admin
from app.schemas.secrets import ManagedSecretDto, ManagedSecretRevealDto, ManagedSecretSetIn
from app.services import managed_secrets

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/secrets", tags=["secrets"])


def _require_managed(key: str) -> str:
    if not managed_secrets.is_managed(key):
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="That key isn't one that can be managed here.",
        )
    return key


@router.get("", response_model=list[ManagedSecretDto])
async def list_secrets(_: Any = Depends(require_master_admin)) -> list[dict[str, Any]]:
    return await managed_secrets.list_managed_secrets()


@router.get("/{key}/reveal", response_model=ManagedSecretRevealDto)
async def reveal_secret(
    key: str,
    current_user: Any = Depends(require_master_admin),
) -> dict[str, Any]:
    _require_managed(key)
    logger.warning(
        "AUDIT managed-secret reveal: key=%s by=%s (%s)", key, current_user.email, current_user.id
    )
    return await managed_secrets.reveal_secret(key)


@router.put("/{key}", response_model=ManagedSecretDto)
async def set_secret(
    key: str,
    payload: ManagedSecretSetIn,
    current_user: Any = Depends(require_master_admin),
) -> dict[str, Any]:
    _require_managed(key)
    value = payload.value.strip()
    if not value:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail="Enter a value, or clear the saved one to use the default.",
        )
    return await managed_secrets.set_secret(key, value, current_user.id)


@router.delete("/{key}", response_model=ManagedSecretDto)
async def clear_secret(
    key: str,
    _: Any = Depends(require_master_admin),
) -> dict[str, Any]:
    _require_managed(key)
    return await managed_secrets.delete_secret(key)


@router.post("/{key}/test", response_model=ManagedSecretDto)
async def test_secret(
    key: str,
    _: Any = Depends(require_master_admin),
) -> dict[str, Any]:
    _require_managed(key)
    return await managed_secrets.test_secret(key)

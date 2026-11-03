from __future__ import annotations

from typing import Any

from fastapi import APIRouter, Depends, File, Request, UploadFile, status
from fastapi.responses import Response

from app.api.routes.design_workshop_oversight import artisan_import_history, run_artisan_import
from app.api.routes.design_workshops import _require_designer
from app.core.deps import get_current_user
from app.services.artisan_xlsx import build_artisan_pro_forma, pro_forma_filename
from app.services.design_workshops import load_workshop_or_404
from app.services.xlsx_report import xlsx_response

router = APIRouter(prefix="/design-workshops", tags=["design-workshops"])


@router.get("/{workshop_id}/roster/pro-forma")
async def download_roster_pro_forma(
    workshop_id: str, current_user: Any = Depends(get_current_user)
) -> Response:
    _require_designer(current_user)
    workshop = await load_workshop_or_404(workshop_id, current_user, for_edit=True)
    return xlsx_response(build_artisan_pro_forma(workshop=workshop), pro_forma_filename(workshop))


@router.post("/{workshop_id}/roster/upload", status_code=status.HTTP_201_CREATED)
async def upload_roster(
    request: Request,
    workshop_id: str,
    file: UploadFile = File(...),
    current_user: Any = Depends(get_current_user),
) -> dict[str, Any]:
    _require_designer(current_user)
    workshop = await load_workshop_or_404(workshop_id, current_user, for_edit=True)
    return await run_artisan_import(workshop, file, request, current_user)


@router.get("/{workshop_id}/roster/imports")
async def list_roster_imports(
    workshop_id: str, current_user: Any = Depends(get_current_user)
) -> dict[str, Any]:
    await load_workshop_or_404(workshop_id, current_user)
    return await artisan_import_history(workshop_id)

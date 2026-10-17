"""``/api/design-workshops/{workshop_id}/roster`` — the designer's own artisan-list import (C11).

Owner decision C11 (2026-10-17): designers may import their own workshop's roster from the
artisan-list spreadsheet, under the same delete-after-reading and audit rules as the officer's
import. Until then only the assigners on ``/design-workshop-oversight`` could, and a designer typed
thirty artisans one by one.

ONE PIPELINE, TWO DOORS. Everything after the door — the venue-location check, the bounded read,
the parse off the event loop, the Details-sheet guard, ``services/artisan_import`` and its
``DwArtisanImport`` ledger, ``del content`` — is
``design_workshop_oversight.run_artisan_import``, called rather than copied.

THE DOOR IS ``load_workshop_or_404(..., for_edit=True)``, which is what every stage write uses: the
workshop's own designers (creator and granted viewers inside ``DESIGN_WORKSHOP_ROLES``) and admins,
refused while the report is frozen for review, and refused to anybody holding a post on it. The
import writes stage-3 participant rows, so it is a stage write and asks exactly that question.

Paths are two segments deep under ``/design-workshops/{id}``, so ``GET /{workshop_id}`` on the
shared prefix cannot swallow them.
"""

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
    """The artisan-list pro-forma, already filled in with this workshop's Details sheet."""
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
    """Import this workshop's artisan list. The workbook is read once and never stored."""
    _require_designer(current_user)
    workshop = await load_workshop_or_404(workshop_id, current_user, for_edit=True)
    return await run_artisan_import(workshop, file, request, current_user)


@router.get("/{workshop_id}/roster/imports")
async def list_roster_imports(
    workshop_id: str, current_user: Any = Depends(get_current_user)
) -> dict[str, Any]:
    """This workshop's accepted artisan-list uploads, newest first — whoever made them."""
    await load_workshop_or_404(workshop_id, current_user)
    return await artisan_import_history(workshop_id)

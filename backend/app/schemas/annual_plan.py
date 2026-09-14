"""Request bodies for ``/api/annual-plan`` — the ministry's annual directory of planned workshops.

TWO BODIES ONLY, AND THE UPLOAD HAS NONE. The upload is a multipart form, so its two scalars
(``planYear``, ``withdrawAbsent``) are ``Form()`` fields validated by helpers in the route — the
rule ``questionnaire_forms._kind_or_422`` states, and the trap ``upload_questionnaire`` names in its
own docstring: a scalar left as a bare default beside an ``UploadFile`` is read off the QUERY STRING
by FastAPI, and a client that put it in the body has it silently ignored under a 201.
"""

from __future__ import annotations

from typing import Annotated

from pydantic import Field, model_validator

from app.schemas.common import APIModel
from app.schemas.design_workshop_viewers import MAX_DESIGN_WORKSHOP_VIEWERS
from app.schemas.design_workshops import REPORT_TEMPLATE_IDS


class AnnualPlanPromoteRequest(APIModel):
    """Who the promoted workshop is for.

    THE SAME TWO DESIGNER FIELDS ``DesignWorkshopCreate`` CARRIES, WITH THE SAME MEANINGS AND THE
    SAME BOUNDS — ``designerUserId`` names the LEAD whose profile is copied into stage 1 and stage
    3, ``designerUserIds`` names everybody who gets a ``DesignWorkshopViewer`` row. Both doors write
    into the same table through the same helper, so two doors with two different caps would be two
    rules for one column; ``schemas/design_workshops.py`` writes that argument out where
    ``designerUserIds`` is declared, and ``tests/test_annual_plan_promotion.py::
    test_the_promote_body_is_bounded_exactly_as_the_create_body_is`` pins the three numbers against
    it so they cannot drift apart.

    RESTATED RATHER THAN REUSED, though. ``DesignWorkshopCreate`` also carries fourteen create-form
    columns — title, craft, cluster, state, district, the two dates — that the DIRECTORY already
    knows, having read them off the ministry's own sheet. A promote body that accepted them would
    let a promotion contradict the plan row it came from, with no record anywhere of which of the
    two the ministry actually issued. The plan row is the source; this body says only who it is for.

    ``templateId`` IS THE THIRD FIELD, AND IT IS NOW BOUNDED BY THE SAME CLOSED SET THE OTHER TWO
    DOORS USE. It reaches ``DesignWorkshop.templateId``, which is plain ``TEXT`` with no enum and no
    CHECK behind it, and ``resolve_template_id`` hands an unknown header value back verbatim while
    ``report_templates.template()`` falls back to the DCH standard for anything it does not know.
    So a typo — ``"DCH_STANDRD"``, or ``"PHOTO-CATALOGUE"`` for ``PHOTO_CATALOGUE`` — used to be a
    201 that stored a template id nothing offers: every report printed the DCH standard until stage
    20 was answered, and the bogus token went into the ``DwReportExport`` provenance register, where
    the report-history screen renders it as the NAME of the template a file was generated with.
    ``DesignWorkshopCreate._known_template`` and ``DesignWorkshopUpdate._known_status_and_template``
    both 422 the identical value, and the second of those spells out why: a template typo "was
    silently accepted and then quietly produced a different report from the one the designer chose".
    A third door onto one column answering 201 was two rules for one column — the very thing the
    designer-field paragraph above says this body exists not to be.
    """

    designerUserId: str | None = Field(default=None, max_length=64)
    designerUserIds: list[Annotated[str, Field(max_length=64)]] = Field(
        default_factory=list, max_length=MAX_DESIGN_WORKSHOP_VIEWERS
    )
    templateId: str = Field(default="DCH_STANDARD", max_length=48)

    @model_validator(mode="after")
    def _known_template(self) -> AnnualPlanPromoteRequest:
        # THE SAME WORDING AS THE OTHER TWO DOORS, character for character, so a client that meets
        # this refusal on one route recognises it on the others.
        if self.templateId not in REPORT_TEMPLATE_IDS:
            raise ValueError(f"templateId must be one of {', '.join(sorted(REPORT_TEMPLATE_IDS))}")
        return self


class AnnualPlanEntryUpdate(APIModel):
    """The one field an administrator may correct on a single row without re-uploading the sheet.

    DELIBERATELY NOT THE WHOLE ROW, and this is the decision most likely to be "fixed" by somebody
    adding a venue field to it. Every other column on ``AnnualPlanEntry`` has exactly ONE writer —
    the workbook — and the whole of idempotency rests on that: the next upload of the corrected
    sheet compares the stored value against the sheet's and finds them different, so a hand-typed
    correction is silently REVERTED, the change report shows it flipping back, and there is no
    record anywhere that anybody ever made it.

    Remarks are the exception because they are the administrator's own note ABOUT the plan row,
    which is the one thing the ministry's sheet does not own — and even they are overwritten by an
    upload whose Remarks column is filled in, which is why the screen says so beside the field.
    """

    notes: str | None = Field(default=None, max_length=2000)

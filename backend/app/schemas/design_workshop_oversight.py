"""The request bodies of the sixth scope: who supervises a design & prototype workshop.

Four models, all small, each carrying one decision a reader will otherwise re-litigate.

**THIS HEADER SAID "Two models" UNTIL 0.0.12 AND IT IS CORRECTED RATHER THAN DELETED**, because the
two that arrived are the two halves of the same change and the reason they arrived is worth keeping:
the assignment screen at ``/officers`` was ADD-ONLY. It could name a designer and could neither show
who else held the workshop nor take anybody off it, and it could not open a workshop at all — a
Ministry Admin is outside ``DESIGN_WORKSHOP_CREATOR_ROLES``, so the ordinary create button 403s them.
:class:`DesignWorkshopDesignersIn` is the whole-set write that closes the first, and
:class:`DesignWorkshopOversightCreateIn` is the third creation door that closes the second.
"""

from pydantic import BaseModel, ConfigDict, Field

#: The most accounts one workshop may be opened for, or named on in one set write.
#:
#: IMPORTED FROM THE VIEWERS' OWN SCHEMA RATHER THAN RETYPED, exactly as ``DesignWorkshopCreate``
#: imports this same name and as the inspector set's own body imports its cap of 25 from the
#: inspectors service: the create door, the viewers PUT and the set write below all land in ONE
#: table, and a body that accepted a set the viewers screen would refuse is one list with two rules.
#: ``lib/designWorkshops.MAX_NAMED_DESIGNERS`` is the web's mirror of the same number and says so.
#:
#: (The inspector body is DESCRIBED rather than SPELLED here, and that is not squeamishness.
#: ``tests/test_dw_inspector_scope_gate.py``'s sweep forbids the inspection scope's four names as
#: LOWERCASED TEXT anywhere outside the three files that feature owns — a blunt registry, chosen
#: because the drift it defends against is somebody reaching for an autocompleted symbol — and that
#: class name contains one of them. Nothing here reads the inspection scope; spelling the class in
#: prose would simply make the sweep red.)
from app.schemas.design_workshop_viewers import MAX_DESIGN_WORKSHOP_VIEWERS


class DesignWorkshopOversightIn(BaseModel):
    """PUT the whole oversight of one workshop: who is its AD and who is its RD.

    **BOTH FIELDS ARE OPTIONAL AND AN EXPLICIT ``null`` UNASSIGNS.** Omitting a key leaves that
    capacity exactly as it stands; sending ``null`` deletes the row. Those are THREE different
    requests — set, leave alone, unassign — and a single ``userIds`` list, which is the shape the
    inspector roster's own body uses one scope over, could express only two of them: a list cannot
    say WHICH capacity a missing name is missing from. The route reads
    ``model_fields_set`` to tell "omitted" from "explicitly null", which is the same
    ``exclude_unset`` discipline ``ArtisanUpdate`` documents.

    **THE TWO TRAVEL TOGETHER** because they are chosen together on one screen, and the response is
    the set as the SERVER now holds it rather than an echo of what was sent — which is what stops
    two administrators on one screen each believing their own payload was the outcome.

    ``extra="forbid"`` so that ``assistant_director_id`` — the snake_case spelling somebody will
    inevitably try — is a 422 naming the field rather than a silent no-op that reports success and
    changes nothing.

    ``max_length=64`` on both because a cuid is 25 characters and no id this repository issues is
    longer; the bound exists so a hostile body cannot make the ``IN`` list in
    ``assert_may_hold`` arbitrarily large. It deliberately does NOT restrict the ALPHABET — the
    service holds back ids carrying characters Postgres cannot store in a ``text`` comparison and
    routes them into the same "no account exists with this id" refusal, which is one message and one
    code path rather than two.
    """

    assistantDirectorId: str | None = Field(default=None, max_length=64)
    regionalDirectorId: str | None = Field(default=None, max_length=64)

    model_config = ConfigDict(extra="forbid")


class DesignWorkshopDesignerIn(BaseModel):
    """PUT the designer a workshop is FOR.

    **A SEPARATE BODY AND A SEPARATE ROUTE FROM THE TWO CAPACITIES ABOVE**, and that is a decision
    rather than a filing accident. The two capacities are rows in ``DesignWorkshopOversight``; the
    designer is not, and must never become one. Who a workshop is FOR already has an owner — the
    promoted ``DesignWorkshop.designerName`` column, the ``DesignWorkshopViewer`` row, and the
    stage 1 / stage 3 copy the prefill makes — so a capacity row saying "the designer is X" beside a
    column saying "the designer is Y" would be two answers to one question, and the report prints
    the column.

    The two also FAIL differently, which is the practical half of the same argument: naming an
    officer writes one row, while naming a designer grants workshop access, copies twenty profile
    fields into two stages and moves a promoted column. Folding them into one body would mean one
    422 that could not say which half of the request it was refusing.

    ``designerId`` IS REQUIRED AND THERE IS NO UNASSIGN. Removing a workshop's designer is not a
    thing this product can express: the viewer row would have to go, the stage-1 header would have
    to be blanked, and the promoted column with it — which is the write
    ``design_workshops._coerce_promoted`` exists to stop happening by accident. Somebody who wants
    that is replacing the designer, which is this route.

    ── AND IT IS NO LONGER THE ONLY DESIGNER BODY ON THIS PREFIX (0.0.12) ────────────────────────

    :class:`DesignWorkshopDesignersIn` is the whole-TEAM write beside it, and the two are not
    alternatives: **this one answers "whose name is on the report", that one answers "who may open
    the workshop".** A workshop is run by two designers alongside a master craftsperson and a
    reviewing officer, and every one of them needs the 22 stages; exactly one of them reaches the
    .docx's ``dc:creator``. Both facts already existed on the CREATE door as ``designerUserId``
    beside ``designerUserIds``; this prefix simply had only half of the pair until now.

    **THE "NO UNASSIGN" RULE SURVIVES THE PLURAL BODY AND IS ENFORCED THERE TOO.** The set write
    cannot be used to reach the state this class refuses — a workshop whose designer is nobody. Its
    route refuses a body that would take the LEAD off without naming a replacement, in the same
    words as the paragraph above, so the rule is spelled once as an argument and enforced on both
    doors. What the set write DOES add is the removal of a CO-designer, which is a different act:
    that person's name is on no document, and before 0.0.12 a Ministry Admin had no route anywhere
    that could take their access away.
    """

    designerId: str = Field(min_length=1, max_length=64)

    model_config = ConfigDict(extra="forbid")


class DesignWorkshopDesignersIn(BaseModel):
    """PUT the whole set of designers a workshop is FOR, and say which of them leads it.

    ── WHY A WHOLE-SET BODY AND NOT ``POST``/``DELETE`` PER PERSON ───────────────────────────────

    Because every sibling on this wire is one — ``DesignWorkshopViewersIn`` and the inspector set's
    own body in ``schemas/design_workshop_inspections`` are both ``userIds`` and nothing else — and
    a third SHAPE for the same question is a third thing to reason about on a screen that now draws
    all three panels four inches apart. (That second class is named by its module rather than by its
    symbol for the reason the ``MAX_DESIGN_WORKSHOP_VIEWERS`` import above sets out: the inspection
    scope's name sweep is a lowercased text match and does not know prose from a call.) It also keeps the property this codebase prizes on every assignment write:
    validation runs to completion before anything is written, so one ineligible id refuses the whole
    call and names every account it objected to, rather than applying the good half and leaving an
    administrator to guess which name failed.

    ⚠ **A WHOLE-SET BODY IS NOT A WHOLE-SET WRITE, AND THE DIFFERENCE IS THE POINT.** The route
    DIFFS this list against the rows that exist and then calls ``attach_the_named_designer`` per
    added id and ``remove_one_viewer`` per removed id. It must never call ``replace_viewers``:
    ``services/design_workshop_grants.py`` is a fourth writer of ``DesignWorkshopViewer``, so a
    whole-set replace destroys a viewer row a concurrent join-card redemption created in the same
    second — the exact hazard ``attach_the_named_designers``' own docstring refuses to take on.

    ``leadUserId`` IS OPTIONAL AND MUST BE ONE OF ``userIds`` WHEN IT IS SENT. Omitted means "leave
    the lead exactly as it stands", which is the ordinary save: adding a co-designer must not move
    whose profile is copied into stage 1 and whose name the report carries. Sending a lead who is
    not in the set would be a body that names somebody as the workshop's designer and does not give
    them access to it, so it is a 422 rather than a silent promotion of the first id.

    ``userIds`` MAY NOT BE EMPTY WHERE THE WORKSHOP HAS A NAMED DESIGNER — see
    :class:`DesignWorkshopDesignerIn`. "Nobody is the designer" is not an expressible state, and a
    body that asked for it would have to blank the promoted ``designerName`` column, which is the
    write ``_coerce_promoted`` exists to stop happening by accident. The refusal lives on the route
    because it needs the workshop row to answer.

    ``max_length`` is :data:`MAX_DESIGN_WORKSHOP_VIEWERS`, imported rather than chosen, because this
    body and the viewers PUT write the same table.

    ``extra="forbid"`` so that ``designerIds`` or ``lead`` — the two spellings somebody will
    inevitably try — are a 422 naming the field rather than a silent no-op reporting success.
    """

    userIds: list[str] = Field(default_factory=list, max_length=MAX_DESIGN_WORKSHOP_VIEWERS)
    leadUserId: str | None = Field(default=None, max_length=64)

    model_config = ConfigDict(extra="forbid")


class DesignWorkshopOversightCreateIn(BaseModel):
    """Open a design & prototype workshop from the oversight screen. **THE THIRD CREATION DOOR.**

    ── WHY A THIRD DOOR RATHER THAN A WIDER GATE ────────────────────────────────────────────────

    ``POST /design-workshops`` is gated by ``assert_can_create_design_workshops``, i.e.
    ``DESIGN_WORKSHOP_CREATOR_ROLES`` = ``{ADMIN, MASTER_ADMIN}``. **A MINISTRY_ADMIN IS NOT AN
    ADMIN ANYWHERE IN THIS CODEBASE** (``core/deps.py``'s long warning says so in as many words),
    and they are the primary user of ``/officers`` — so the one screen that decides who a workshop
    is for could not open one. The annual-plan directory hit the identical wall and its answer is
    the precedent this follows verbatim: ``POST /annual-plan/{id}/promote`` creates a workshop
    through the SAME opener behind ``require_annual_plan_manager``, and its docstring records why —
    *"widening that set to fit would hand every ministry admin the ordinary create button as well.
    Two doors, two gates, one creation path."* This is the third of those, and
    ``DESIGN_WORKSHOP_CREATOR_ROLES`` is untouched (``tests/test_design_workshop_gate.py`` reads
    ``frontend/lib/permissions.ts`` to hold the two copies identical).

    ── THE FIELD LIST IS ``DesignWorkshopCreate``'S, MINUS THE THINGS AN OFFICER DOES NOT ANSWER ──

    ``templateId``, ``notes`` and ``workshopId`` are deliberately absent. The first is the report
    format, chosen by whoever generates the report; the second is the designer's own scratch column;
    the third links a 22-stage record to a ``Workshop`` row, which is a designer's cross-reference
    and not an officer's. Everything else is the SAME seven columns the two existing doors carry,
    because they are the seven a ministry officer has on the paper in front of them.

    Only ``title`` is required, exactly as on the other two doors and for the reason
    ``DesignWorkshopCreate`` gives: a workshop is opened before its details are settled, and stage 1
    is where they are actually enforced.

    ``designerUserId`` and ``designerUserIds`` keep their create-door meanings EXACTLY — the lead,
    and everybody who gets a viewer row — because the route hands both to
    ``design_workshops.named_designer_team``, the same pure function both other doors call. A third
    reading of those two fields is how the three doors come to disagree about whose name reaches the
    report.
    """

    title: str = Field(min_length=1, max_length=220)
    workshopKind: str | None = Field(default=None, max_length=48)
    designerUserId: str | None = Field(default=None, max_length=64)
    designerUserIds: list[str] | None = Field(default=None, max_length=MAX_DESIGN_WORKSHOP_VIEWERS)
    craftName: str | None = Field(default=None, max_length=160)
    clusterName: str | None = Field(default=None, max_length=160)
    state: str | None = Field(default=None, max_length=80)
    district: str | None = Field(default=None, max_length=80)
    #: ``yyyy-mm-dd``, exactly what the create form's two hidden inputs submit. Parsed by the route
    #: through ``design_workshops._parse_date`` — the same function the ordinary door uses, so a
    #: malformed date is DROPPED on both rather than refused on one and stored on the other.
    startDate: str | None = Field(default=None, max_length=32)
    endDate: str | None = Field(default=None, max_length=32)

    model_config = ConfigDict(extra="forbid")

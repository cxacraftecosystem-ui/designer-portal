"""The request bodies of the sixth scope: who supervises a design & prototype workshop.

Two models, both tiny, both carrying one decision each that a reader will otherwise re-litigate.
"""

from pydantic import BaseModel, ConfigDict, Field


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
    """

    designerId: str = Field(min_length=1, max_length=64)

    model_config = ConfigDict(extra="forbid")

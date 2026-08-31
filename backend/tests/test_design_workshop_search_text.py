"""What ``DwStageEntry.searchText`` contains, and the guard that keeps it in step with ``data``.

Every assertion here is about a decision that is invisible on screen and expensive to get wrong:
which of a stage row's answers a researcher can find by typing, which are deliberately unfindable,
and — the one this file exists for — whether a SECOND writer of ``data`` has appeared that does not
maintain the column beside it. None of it needs a database; the renderer is pure, and the writer
sweep reads source.

The database half is ``tests/test_stage_search_text.py``.
"""

from __future__ import annotations

import ast
from pathlib import Path
from types import SimpleNamespace
from typing import Any

import app.services.stage_definitions  # noqa: F401  - installs the registry
from app.services import design_workshop_data as dwd
from app.services.records import _IDENTITY_KEYS
from app.services.stage_schema import EntitySpec, FieldType, stages

APP = Path(__file__).resolve().parents[1] / "app"


def _entity(key: str) -> EntitySpec:
    found = dwd.entity_by_key(key)
    assert found is not None, f"{key} is not in this build's registry"
    return found[1]


# --------------------------------------------------------------------------------------
# What goes in
# --------------------------------------------------------------------------------------


def test_a_typed_answer_is_findable_by_the_words_the_designer_typed():
    """The whole point, stated as one assertion.

    §6.1 of ``docs/DECISION-design-workshop-data-in-view-data.md``: "a search for 'indigo' finds a
    workshop whose title or craft name says indigo, and does not find the workshop whose stage 5
    dye-bath answer says it." This is the second half becoming true.
    """
    text = dwd.entry_search_text(
        _entity("workshopSetup"),
        {"workshopTitle": "Indigo dyeing at Barpali", "venue": "Weavers' Service Centre"},
    )
    assert "Indigo dyeing at Barpali" in text
    assert "Weavers' Service Centre" in text


def test_an_enum_is_stored_as_its_LABEL_and_not_as_the_token():
    """The reason a rendered column beats a trigram index over ``data::text``.

    A designer picks "Design & Prototype Development" from a dropdown and the row holds
    ``DESIGN_PROTOTYPE_DEVELOPMENT``. Over the raw JSON, the one string the product itself showed
    that designer would find nothing, and the reader would have to know the token.
    """
    text = dwd.entry_search_text(
        _entity("workshopSetup"), {"workshopKind": "DESIGN_PROTOTYPE_DEVELOPMENT"}
    )
    assert "DESIGN_PROTOTYPE_DEVELOPMENT" not in text
    assert text  # it rendered SOMETHING - the label, whatever the registry currently words it as
    label = next(
        spec for spec in _entity("workshopSetup").fields if spec.key == "workshopKind"
    )
    from app.services.stage_schema import enum_label

    assert enum_label(label.enum, "DESIGN_PROTOTYPE_DEVELOPMENT") in text


def test_rich_text_is_flattened_to_the_words_inside_it():
    """A RICH_TEXT value is a document, and ``rich_text.to_plain``'s own docstring names a search
    index as one of its two consumers. Without this branch the column would hold the JSON's braces —
    the same shape that once printed ``{'blocks': [{'kind': 'PARAGRAPH', …}]}`` into a report
    submitted to a ministry (see ``report_builder.format_value``'s RICH_TEXT arm).

    Against a REAL registry field rather than a fabricated one, so the branch is exercised through
    the same ``FieldSpec`` a designer's prose actually reaches it as.
    """
    entity = _entity("introduction")
    spec = next(f for f in entity.fields if f.type is FieldType.RICH_TEXT)
    doc = {"blocks": [{"kind": "PARAGRAPH", "spans": [{"text": "The cluster dyes with indigo"}]}]}
    text = dwd.entry_search_text(entity, {spec.key: doc})
    assert text == "The cluster dyes with indigo"


def test_media_references_never_reach_the_column():
    """Nobody types a cuid, and a ``dwlocal:`` reference is a blob on somebody's phone.

    A media id in this column would be noise a researcher cannot use and bytes on every row.
    """
    text = dwd.entry_search_text(
        _entity("workshopSetup"),
        {"workshopTitle": "Ikat", "coverPhoto": "cmedia123abc", "sanctionDocument": "dwlocal:xyz"},
    )
    assert text == "Ikat"


def test_numbers_and_dates_are_left_out_because_the_rendering_is_not_what_a_person_types():
    """``format_value`` prints 6500 as "₹ 6,500.00" and 2026-02-10 as "10 Feb 2026".

    A column carrying those would LOOK like it covered numbers and dates while failing the two
    obvious queries — the wrong-answer-dressed-as-right shape this repository keeps un-shipping.
    Saying they are not searched is honest; half-searching them is not.
    """
    text = dwd.entry_search_text(
        _entity("workshopSetup"),
        {"workshopTitle": "Ikat", "startDate": "2026-02-10", "durationDays": 14},
    )
    assert text == "Ikat"


def test_the_column_is_deduplicated_and_deterministic():
    """A backfill that produced a different string on a second run over unchanged rows would make
    its own "rows touched" count meaningless, and a repeated value is bytes for no recall.
    """
    data = {"workshopTitle": "Ikat", "craftName": "Ikat", "clusterName": "Barpali"}
    first = dwd.entry_search_text(_entity("workshopSetup"), data)
    assert first == dwd.entry_search_text(_entity("workshopSetup"), data)
    assert first.count("Ikat") == 1


def test_a_row_with_nothing_text_shaped_renders_the_empty_string():
    """Which both writers store as NULL. "Nothing searchable" and "not computed yet" are different
    facts and are indistinguishable to a ``contains``, so the column carries NULL for both and the
    backfill's ``searchText IS NULL`` resume point stays honest for the life of the table.
    """
    assert dwd.entry_search_text(_entity("workshopSetup"), {"durationDays": 14}) == ""
    assert dwd.entry_search_text(_entity("workshopSetup"), None) == ""
    assert dwd.entry_search_text(_entity("workshopSetup"), "not a dict") == ""


# --------------------------------------------------------------------------------------
# What is kept OUT, and the sentence in another module that depends on it
# --------------------------------------------------------------------------------------


def test_a_participants_contact_details_are_never_searchable():
    """**THE EXCLUSION THAT PROTECTS A RULING MADE ELSEWHERE.**

    ``access.py``'s banner above ``REVISION_REDACTED_FIELDS`` accepts that clearing ``Artisan.phone``
    leaves the number in every stage row that referenced her, on the stated ground that "a
    ``DwStageEntry`` is not indexed by identity number … It is a RESIDUE, not a ledger." A column
    that made those rows matchable by typing the number would falsify that in one commit, and hand
    every professor a reverse lookup ("which workshops is this person in") that no surface in this
    product offers.
    """
    participant = _entity("participant")
    text = dwd.entry_search_text(
        participant,
        {
            "name": "Rekha Sahu",
            "phone": "9876543210",
            "email": "rekha@example.org",
            "aadhaarNumber": "XXXX XXXX 9012",
            "artisanCardNo": "XXXX XXXX 3456",
        },
    )
    assert "Rekha Sahu" in text, "the NAME is what a researcher searches for and must stay"
    for secret in ("9876543210", "rekha@example.org", "9012", "3456"):
        assert secret not in text


def test_every_identity_key_records_knows_about_is_excluded_here():
    """The two lists are retyped rather than imported (this module is pure; ``records`` opens the
    database), so they are pinned to each other instead of trusted.

    A key added to ``records._IDENTITY_KEYS`` and not here would be a number that
    ``_redact_sensitive`` masks on the way out and that this column made searchable on the way in.
    """
    assert set(_IDENTITY_KEYS) <= dwd.UNSEARCHABLE_FIELD_KEYS


def test_the_pehchan_carry_is_excluded_under_the_key_the_registry_actually_uses():
    """``artisanCardNo``, which is NOT in ``records._IDENTITY_KEYS``.

    The registry stores the Pehchan card's masked carry under its own spelling
    (``stage_definitions``' ``fromref("artisanCardNo", …)``), which is exactly why
    ``_redact_sensitive``'s by-name walk does not reach it either. Excluding it by type is
    impossible — it is declared TEXT — so it has to be named.
    """
    assert "artisanCardNo" in dwd.UNSEARCHABLE_FIELD_KEYS
    assert "artisanCardNo" not in set(_IDENTITY_KEYS)


def test_no_contact_typed_field_anywhere_in_the_registry_is_searchable():
    """Held to the whole registry rather than to the one entity above, so a PHONE or EMAIL field
    added to a future stage cannot arrive searchable by default.
    """
    contact = {FieldType.PHONE, FieldType.EMAIL}
    for stage in stages():
        for entity in stage.entities:
            for spec in entity.fields:
                if spec.type in contact:
                    assert not dwd._searchable(spec), f"{entity.key}.{spec.key}"


# --------------------------------------------------------------------------------------
# The designer's own questions
# --------------------------------------------------------------------------------------


def _custom_spec(**kwargs: Any) -> Any:
    """A duck-typed ``CustomFieldSpec``. The renderer never imports that class — see its docstring."""
    base = {
        "key": "q1",
        "type": FieldType.TEXT,
        "option_label": None,
    }
    base.update(kwargs)
    return SimpleNamespace(**base)


def test_a_designers_own_question_is_searchable():
    """**THE ANSWERS THAT MOST NEED THIS COLUMN.**

    A registry field's wording is shared by every workshop, so a researcher can read the form to
    learn what to type. A custom field is one designer's question asked in one workshop in words
    nobody else can guess, and until this column existed its answers were reachable only by opening
    the workshop that had to be found some other way.
    """
    specs = [_custom_spec(key="dyeBath"), _custom_spec(key="looms", type=FieldType.INT)]
    text = dwd.custom_search_text(specs, {"dyeBath": "Indigo, three dips", "looms": 12})
    assert text == "Indigo, three dips"


def test_a_custom_choice_is_stored_as_the_designers_own_label():
    """Through the field's OWN ``option_label``: a designer's options are per-workshop rows and
    ``stage_schema.enum_label`` has never heard of them.
    """
    spec = _custom_spec(
        key="finish",
        type=FieldType.ENUM,
        option_label=lambda value: {"NAT": "Natural dye"}.get(str(value), str(value)),
    )
    assert dwd.custom_search_text([spec], {"finish": "NAT"}) == "Natural dye"


def test_a_custom_multi_choice_renders_every_option_it_holds():
    spec = _custom_spec(
        key="dyes",
        type=FieldType.MULTI_ENUM,
        option_label=lambda value: {"IND": "Indigo", "MAD": "Madder"}.get(str(value), str(value)),
    )
    text = dwd.custom_search_text([spec], {"dyes": ["IND", "MAD"]})
    assert "Indigo" in text and "Madder" in text


def test_a_custom_field_named_like_an_identity_number_is_still_excluded():
    """A designer may write their own "phone" question, and the exclusion is by KEY, so it holds
    there too. The residue argument does not care which half of the form the number was typed into.
    """
    assert dwd.custom_search_text([_custom_spec(key="phone")], {"phone": "9876543210"}) == ""


# --------------------------------------------------------------------------------------
# Naming the stage a hit came from
# --------------------------------------------------------------------------------------


def test_a_stage_is_named_with_its_number_first():
    """A hit that does not say WHICH of twenty-two stages matched is a hit a researcher cannot act
    on, and the number leads because that is how every other surface orders a fortnight of work.
    """
    first = stages()[0]
    assert dwd.stage_label(first.key) == f"Stage {first.number}: {first.title}"


def test_an_unknown_stage_key_is_returned_as_itself():
    """A row can honestly carry a stage key this build has never heard of — a phone one release
    ahead. The raw key is still something a reader can search the registry for; an invented title
    would not be.
    """
    assert dwd.stage_label("STAGE_FROM_THE_FUTURE") == "STAGE_FROM_THE_FUTURE"


# --------------------------------------------------------------------------------------
# THE SINGLE-WRITER GUARD
# --------------------------------------------------------------------------------------

#: Every place in ``backend/app`` that writes ``DwStageEntry``, as ``module:enclosing function``.
#:
#: **THIS IS THE STRUCTURAL ANSWER TO §6.1's OBJECTION TO THIS WHOLE DESIGN**, which was "a second
#: copy of every answer — which can disagree with ``data`` the moment a write path forgets it".
#: A promise to be careful is not an answer; a test that fails on the fourth writer is.
#:
#: **Adding an entry here obliges you to write ``searchText`` in the same statement**, unless the
#: write does not touch ``data`` at all (the sweep below is the only such entry today, and it is
#: marked). A writer that sets ``data`` and leaves this column standing does not fail loudly — it
#: answers a search from a stale copy, which is the wrong-answer-dressed-as-right failure the
#: honesty sentence this feature retired existed to prevent.
_KNOWN_STAGE_ENTRY_WRITERS = frozenset(
    {
        # `POST /design-workshops` — seeds `workshopSetup` from the designer's profile. It runs
        # BEFORE `save_stage` ever does and puts a row into essentially every new workshop, so a
        # prefilled workshop would be unsearchable until somebody happened to re-save that stage.
        # One `create`, and it writes `searchText`.
        "services/design_workshops.py:seed_designer_prefill",
        # `PUT /{id}/stages/{key}` — the ordinary stage save. THREE statements, and only two of them
        # owe this column anything: the `update` and the `create` apply the `updates`/`creates` dicts
        # (both of which carry `searchText`), while the `update_many` is the collection sweep and
        # writes ONE timestamp — `{"deletedAt": ...}`. A soft delete leaves the row's answers exactly
        # as they were, so its rendered copy is still correct and rewriting it would be busywork on
        # the one statement that already touches many rows.
        "services/design_workshops.py:save_stage.write_everything",
    }
)

_WRITE_METHODS = frozenset({"create", "create_many", "update", "update_many", "upsert", "delete_many"})


def _stage_entry_write_sites() -> set[str]:
    """Every ``*.dwstageentry.<write>()`` call in ``backend/app``, as ``path:qualified function``.

    Parsed rather than grepped, because the delegate is reached through two receivers (``db`` and a
    transaction's ``tx``) and the enclosing function is what the register above is keyed by — a
    regular expression can see neither.
    """
    def walk(node: ast.AST, scope: list[str], rel: str, sites: set[str]) -> None:
        # `rel` is threaded through rather than closed over: a nested function that captured the
        # loop variable would report every site under the LAST file walked, and the register above
        # would then be pinned to a lie that happens to have the right cardinality.
        for child in ast.iter_child_nodes(node):
            if isinstance(child, ast.FunctionDef | ast.AsyncFunctionDef):
                walk(child, [*scope, child.name], rel, sites)
                continue
            if (
                isinstance(child, ast.Call)
                and isinstance(child.func, ast.Attribute)
                and child.func.attr in _WRITE_METHODS
                and isinstance(child.func.value, ast.Attribute)
                and child.func.value.attr == "dwstageentry"
            ):
                sites.add(f"{rel}:{'.'.join(scope) or '<module>'}")
            walk(child, scope, rel, sites)

    sites: set[str] = set()
    for path in sorted(APP.rglob("*.py")):
        tree = ast.parse(path.read_text(encoding="utf-8"))
        walk(tree, [], path.relative_to(APP).as_posix(), sites)
    return sites


def test_no_third_writer_of_a_stage_entry_has_appeared():
    """**THE GUARD THIS COLUMN'S DESIGN RESTS ON.**

    ``searchText`` is a rendered copy of ``data``, and the only thing that stops the two disagreeing
    is that every writer of one writes the other. There are two. A third that set ``data`` alone
    would leave rows whose search answer is a previous designer's — silently, because nothing about
    a stale string looks wrong.

    If this fails, do not widen the set to make it pass: read
    ``design_workshops.save_stage``'s two write dicts, do the same in the new writer, THEN add it.
    """
    found = _stage_entry_write_sites()
    assert found == _KNOWN_STAGE_ENTRY_WRITERS, (
        "A writer of DwStageEntry has been added or moved. Every writer that touches `data` must "
        "write `searchText` in the same statement - see the register above this test.\n"
        f"  added:   {sorted(found - _KNOWN_STAGE_ENTRY_WRITERS)}\n"
        f"  missing: {sorted(_KNOWN_STAGE_ENTRY_WRITERS - found)}"
    )


def test_both_clients_cap_the_named_stages_at_the_same_number():
    """The web and the handset must not disagree about how many stages a hit names before counting.

    A hit can legitimately be in a dozen of a workshop's twenty-two stages, and both clients name a
    few and COUNT the rest — rule 10 applied to a subtitle. Read off disk in both files rather than
    trusted, because the two constants are hand-kept copies of one decision and the failure mode is
    silent: one client would say "and 9 more" where the other said "and 6 more" about the same
    workshop, and a researcher comparing the two screens has no way to tell which is lying.

    The two files are the search PAGE and the search SCREEN — this is a display cap, not a wire
    value, so it is deliberately not something the server sends.
    """
    root = APP.parents[1]
    web = (root / "frontend" / "app" / "(protected)" / "search" / "page.tsx").read_text(
        encoding="utf-8"
    )
    android = (
        root
        / "android"
        / "app"
        / "src"
        / "main"
        / "java"
        / "com"
        / "designprototype"
        / "workshop"
        / "ui"
        / "SearchScreen.kt"
    ).read_text(encoding="utf-8")
    assert "const MATCHED_IN_SHOWN = 3;" in web
    assert "internal const val MATCHED_IN_SHOWN = 3" in android


def test_both_writers_name_the_column_in_their_own_source():
    """A cheap second lock, and it catches the likelier accident: not a NEW writer, but an existing
    one whose ``searchText`` line is deleted while its ``data`` line stays.

    Held against the function's own source text rather than against behaviour, so it costs no
    database and fails at the place the deletion happened.
    """
    source = (APP / "services" / "design_workshops.py").read_text(encoding="utf-8")
    tree = ast.parse(source)
    for name in ("seed_designer_prefill", "save_stage"):
        node = next(
            n
            for n in ast.walk(tree)
            if isinstance(n, ast.FunctionDef | ast.AsyncFunctionDef) and n.name == name
        )
        body = ast.get_source_segment(source, node) or ""
        assert '"searchText"' in body, f"{name} writes `data` and no longer writes `searchText`"

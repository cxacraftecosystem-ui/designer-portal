"""The designer profile → report contract, checked without a database.

``PREFILL_MAP`` is a hand-written table joining two hand-written lists: the profile's writable
columns (``designers.PROFILE_FIELDS``, mirrored by a Prisma model and a Pydantic body) and the
registry's field keys (``stage_definitions``, mirrored by a Kotlin port and a TypeScript one).
Nothing in the running app can notice when that join is wrong, and BOTH ways of being wrong are
silent:

* **A TARGET THAT DOES NOT EXIST.** ``prefill_from_profile`` writes ``{field_key: value}`` into a new
  workshop's stage data. A misspelt key is written successfully — the blob is JSON — under a name no
  form renders and no report prints. The value is saved, and invisible, for ever. ``validate_registry``
  cannot catch it because this table is not part of the registry.

* **A COLUMN NOBODY CARRIES.** This is how the feature came to be four columns out of twenty: the
  profile grew, the map did not, and the only symptom was a designer typing their designation into a
  page that promised it would reach their reports and then watching it not. There is no error to
  read, because nothing failed.

So the two directions are asserted separately, and neither needs Postgres: both are statements about
literals in this repository. Importing ``stage_definitions`` is what installs the twenty-two stages
into the registry, and it is the only cost here.

WHY NOT IN ``test_designer_roster.py``. That module skips itself when ``DATABASE_URL`` does not point
at a local database, and on this project's machines Docker is frequently not running — so putting a
check with no database dependency in there would mean the one guard against a silently-lost profile
column is skipped precisely on the day somebody is working offline and adds a column.
"""

from __future__ import annotations

from datetime import UTC, datetime
from types import SimpleNamespace

# Importing this module is what installs the twenty-two stages into the registry.
import app.services.stage_definitions  # noqa: F401
from app.schemas.designers import DesignerProfileUpdate
from app.services import designers
from app.services.designers import PREFILL_MAP, PROFILE_FIELDS
from app.services.stage_schema import (
    STAGES,
    Cardinality,
    FieldType,
    coerce_value,
    validate_entry,
)

#: Profile columns that may exist WITHOUT being copied into a report, each with its reason.
#:
#: IT WAS EMPTY UNTIL 2026-08-29, AND THAT EMPTINESS WAS THE POINT — read the entry below against
#: this paragraph rather than as a softening of it. The owner's instruction of 2026-08-25 is that everything typed
#: on the Designer Page is master data pre-filled into every report, so the honest state of this set
#: is "nothing is exempt". It exists so that a future column which genuinely must not cross — an
#: internal flag, a private note, something an admin records about a designer rather than something
#: the designer publishes — is exempted HERE, in one line, with its reason beside it, instead of being
#: dropped from ``PREFILL_MAP`` in silence where it is indistinguishable from the omission this whole
#: module exists to catch.
#:
#: ── THE FIRST ENTRY, 2026-08-29, AND IT IS A "NOT YET" RATHER THAN A "NEVER" ────────────────
#:
#: The paragraph above says this set is for a column that must NOT cross. ``experienceMonths`` is not
#: that; it is master data like the years beside it and it SHOULD print. It is here because the
#: receiving box does not exist and creating one is a bigger, separate decision than the API change
#: that added the column — written down here in full so the next reader is not left guessing which
#: kind of entry this is, and so the work it owes is in one place:
#:
#:   1. ``f("designerExperienceMonths", "Designer's experience (months)", INT, S, unit="months",
#:      min_value=0, max_value=11)`` on stage 3's ``workshopPlan``, beside ``designerExperience``.
#:   2. a ``("experienceMonths", "designerExperienceMonths")`` row in ``PREFILL_MAP``.
#:   3. deleting this entry, which ``test_the_exemption_list_cannot_name_a_column_that_is_also_carried``
#:      will then insist on.
#:
#: WHY IT WAS NOT DONE IN THE SAME CHANGE. A new registry field moves ``registry_version()``, which
#: is the refetch signal every client reads, and it owes a re-dump of the 119 KB
#: ``android/app/src/main/assets/design-workshop-schema.json`` plus a re-cut APK —
#: ``test_the_bundled_android_asset_matches_the_registry_it_was_dumped_from`` fails until both
#: happen. It also decides what every future report PRINTS: "14 years" and "6 months" arrive as two
#: separate key-value lines, because ``report_builder`` appends a field's ``unit`` to its value and
#: has no way to join two fields into one. That is a report-layout call for the owner, not a
#: side effect of adding a column to a form.
#:
#: WHAT IT COSTS MEANWHILE, STATED PLAINLY: a designer fills in "and 6 months" on the Designer Page,
#: it saves, it reads back, and the reports generated afterwards say "14 years" exactly as they do
#: today. Nothing is lost — the value is on the profile row and is copied nowhere — but nothing is
#: gained downstream either, and a reader who assumes the months reach the .docx will be wrong.
PREFILL_EXEMPT: dict[str, str] = {
    "experienceMonths": (
        "stage 3 has no designerExperienceMonths box yet; adding one moves registry_version(), "
        "owes a re-dump of the bundled Android asset and a re-cut APK, and decides how the report "
        "prints the pair. See the note above this dict for the three steps that retire this entry."
    ),
}


def _registry_field_keys() -> set[str]:
    """Every non-deprecated field key in the registry, across every stage and every entity.

    Flattened rather than resolved per stage on purpose. ``PREFILL_MAP``'s right-hand side names a
    FIELD KEY and not a (stage, entity, field) triple, because that is what
    ``prefill_from_profile`` returns and what ``create_design_workshop`` spreads into the seed — the
    key alone is the contract, so the key alone is what is checked.

    DEPRECATED FIELDS ARE EXCLUDED, and that is the substance of the check rather than tidiness. A
    field retired with ``deprecated=True`` keeps its key so stored answers stay readable, but no form
    draws it and the report skips it. Prefilling into one would be writing to a box nobody will ever
    look at again — the same invisible-value failure as a typo, arrived at by a different route.
    """
    return {
        field.key
        for stage in STAGES
        for entity in stage.entities
        for field in entity.fields
        if not field.deprecated
    }


def _designer_field(key: str):
    """One field off stage 3's ``workshopPlan``, the singleton the designer's details land on.

    Reached through the stage rather than through the flattened key set above because the tests that
    coerce a VALUE need the FieldSpec's own type, format and bound — the flattened set is only ever
    asked whether a key exists.
    """
    plan = next(s for s in STAGES if s.number == 3).entity("workshopPlan")
    assert plan is not None, "stage 3 has no workshopPlan entity"
    field = plan.field(key)
    assert field is not None, f"workshopPlan has no {key!r} field"
    return field


def test_every_prefilled_profile_column_has_a_receiving_field():
    """Every right-hand key in ``PREFILL_MAP`` is a live registry field.

    This is the typo guard. See the module docstring for why a typo here is written successfully and
    is then invisible for ever.
    """
    known = _registry_field_keys()
    missing = sorted({field_key for _, field_key in PREFILL_MAP if field_key not in known})
    assert missing == [], (
        "PREFILL_MAP names registry fields that do not exist (or are deprecated): "
        + ", ".join(missing)
        + ". prefill_from_profile would write these into DwStageEntry.data under a key no form "
        "renders and no report prints — saved, and invisible."
    )


def test_every_prefilled_profile_column_is_a_real_profile_column():
    """Every LEFT-hand name is a column ``update_profile`` will actually write.

    The mirror of the test above, and it fails in the same silent way: ``prefill_from_profile`` reads
    each column with ``getattr(profile, column, None)``, whose third argument turns a misspelt column
    into ``None`` — which the loop then skips as "the designer has not filled this in". A designer
    who HAD filled it in would see it silently absent from every report, and nothing would have
    raised.
    """
    unknown = sorted({column for column, _ in PREFILL_MAP if column not in PROFILE_FIELDS})
    assert unknown == [], (
        "PREFILL_MAP names profile columns that are not in PROFILE_FIELDS: "
        + ", ".join(unknown)
        + ". getattr(..., None) would silently read these as empty on every workshop."
    )


def test_every_writable_profile_column_is_either_prefilled_or_named_here():
    """Nothing a designer can type is quietly left out of their reports.

    THE DEFECT THIS ENDS is the one the feature actually shipped with: four of twenty columns were
    carried, the other sixteen were typed into a page that said they would be copied into every
    report, and they were not. Nothing failed and there was nothing to read.
    """
    carried = {column for column, _ in PREFILL_MAP}
    forgotten = sorted(set(PROFILE_FIELDS) - carried - set(PREFILL_EXEMPT))
    assert forgotten == [], (
        "these profile columns reach no report and are not exempt: "
        + ", ".join(forgotten)
        + ". Add a PREFILL_MAP row with a receiving FieldSpec, or add the column to PREFILL_EXEMPT "
        "with the reason it must not cross."
    )


def test_the_exemption_list_cannot_name_a_column_that_is_also_carried():
    """A column cannot be both exempt and prefilled — that pair is a stale exemption.

    Cheap, and it catches the realistic sequence: a column is exempted, the decision is later
    reversed and a ``PREFILL_MAP`` row is added, and the exemption with its now-false reason is left
    behind for the next reader to believe.
    """
    carried = {column for column, _ in PREFILL_MAP}
    both = sorted(carried & set(PREFILL_EXEMPT))
    assert both == [], "exempt AND prefilled, so one of the two is stale: " + ", ".join(both)


def test_the_wire_body_accepts_every_column_the_prefill_reads():
    """A column the API cannot be SENT is a column no designer can fill in.

    ``PROFILE_FIELDS`` drives ``update_profile``'s write loop and ``profile_payload``'s read loop, so
    a name present there and absent from ``DesignerProfileUpdate`` is a column that serialises OUT
    and can never be written IN — readable, permanently empty, and prefilled as blank into every
    report. The three lists are maintained by hand in two files; this is the join between them.
    """
    body_fields = set(DesignerProfileUpdate.model_fields)
    unsendable = sorted(set(PROFILE_FIELDS) - body_fields)
    assert unsendable == [], (
        "PROFILE_FIELDS names columns DesignerProfileUpdate cannot carry: "
        + ", ".join(unsendable)
        + ". They would be readable, unwritable, and prefilled as blank into every report."
    )


def test_the_designer_boxes_stage_3_gained_are_all_there():
    """The receiving fields exist on the entity the prefill actually seeds, with the right shapes.

    The tests above check the KEY resolves somewhere in the registry. This checks the stronger thing
    a reader would assume: that the designer's details landed on stage 3's ``workshopPlan`` singleton
    — the entity whose stated purpose is "the designer's own profile" — rather than being scattered.

    NAMED EXPLICITLY RATHER THAN DERIVED FROM ``PREFILL_MAP``, because a test that computed its
    expectation from the table it is checking would pass for any table at all. These are the boxes
    the owner asked for, listed once, by hand.
    """
    stage = next(s for s in STAGES if s.number == 3)
    plan = stage.entity("workshopPlan")
    assert plan is not None, "stage 3 has no workshopPlan entity"

    expected = {
        "designerProfile",
        "designerExperience",
        "designerLocalName",
        "designerDesignation",
        "designerDepartment",
        "designerQualification",
        "designerSpecialisation",
        "designerPhone",
        "designerEmail",
        "designerWebsite",
        "designerAddress",
        "designerCity",
        "designerState",
        "designerPincode",
        "designerEmpanelmentNo",
        "designerEmpanelmentDate",
        "designerPhoto",
        "designerSignature",
        "designerCv",
    }
    present = {f.key for f in plan.fields if not f.deprecated}
    assert expected <= present, "stage 3 is missing designer boxes: " + ", ".join(
        sorted(expected - present)
    )

    # NONE OF THEM MAY BE REQUIRED. A designer who has filled in nothing but their name must still be
    # able to submit stage 3 — the profile is a convenience, not a gate — and `validate_registry`
    # refuses a required field above BASIC in any case, so a `required=True` here would fail the
    # build rather than merely being wrong. Asserted anyway because the failure it would cause
    # (twenty-one new obstacles on the readiness screen of every existing workshop) is worth naming.
    required = sorted(f.key for f in plan.fields if f.key in expected and f.required and f.key != "designerProfile")
    assert required == [], "these designer boxes are required and must not be: " + ", ".join(required)


# --------------------------------------------------------------------------------------
# The narrowing on the way out — a Postgres DateTime landing in a registry DATE
#
# ``prefill_from_profile`` reads twenty-one columns and hands them to ``validate_entry``, which
# coerces each one against the FieldSpec it lands on. Twenty of the twenty-one are strings or an
# int and need nothing. ``empanelmentDate`` is a DateTime column whose target,
# ``designerEmpanelmentDate``, is a registry DATE — and DATE's arm of ``coerce_value`` is
# ``str(raw).strip()[:10]``, which happens to read both ``str(datetime)`` ("2026-03-14 00:00:00+00:00")
# and ``datetime.isoformat()`` ("2026-03-14T00:00:00+00:00") correctly. That coincidence is what the
# ``.date().isoformat()`` in ``prefill_from_profile`` exists to stop relying on: it is the first ten
# characters of a repr, and a repr is not an interface.
#
# NO DATABASE, ON PURPOSE, and it is the same argument as the module docstring's. The behaviour
# under test is a narrowing in one Python branch, not a row in Postgres; ``test_designer_roster``
# creates a real workshop and asserts the copy is a copy, and it skips itself on every machine
# where Docker is not running — which on this project's boxes is most of them. A guard that is
# skipped precisely when somebody is working offline and adds a second date column is not a guard.
# --------------------------------------------------------------------------------------


class _StubProfileTable:
    """Exactly the one call ``prefill_from_profile`` makes, and nothing else.

    Deliberately NOT a mock library and NOT a fake that accepts any method: the point of a stub this
    small is that it fails loudly if the function under test starts doing something else — a second
    query, a write, a different table — rather than absorbing it silently. ``where`` and ``include``
    are recorded so the test can assert the lookup is still by ``userId`` (the profile's unique key)
    rather than, say, by email, which would make the prefill answer for whoever shares an address.
    """

    def __init__(self, profile: object | None) -> None:
        self._profile = profile
        self.calls: list[tuple[dict, dict | None]] = []

    async def find_unique(self, where: dict, include: dict | None = None) -> object | None:
        self.calls.append((where, include))
        return self._profile


class _StubDb:
    """``app.services.designers.db`` with only ``designerprofile`` on it."""

    def __init__(self, profile: object | None) -> None:
        self.designerprofile = _StubProfileTable(profile)


#: A profile with every writable column answered, so the prefill has something to say about all of
#: them. Built from ``PROFILE_FIELDS`` rather than written out as a literal, so a column added to
#: that tuple is answered here automatically and the round-trip test below covers it on the day it
#: lands instead of on the day somebody remembers this fixture exists.
_PROFILE_VALUES: dict[str, object] = {
    "displayName": "Latha Nayak",
    "localName": "ଲତା ନାୟକ",
    "designation": "Assistant Professor",
    "institution": "NIFT Bhubaneswar",
    "department": "Textile Design",
    "qualification": "M.Des (Textile Design), NID Ahmedabad",
    "specialisation": "Handloom weave structures, natural dyeing",
    "experienceYears": 14,
    # The remainder beside it. NOT 0, deliberately: a fixture answering zero would pass equally well
    # against a serializer that had folded 0 into None somewhere, and this pair is the one place in
    # the profile where that fold would be invisible.
    "experienceMonths": 6,
    "biography": "Fourteen years on Odisha's ikat clusters.",
    # The three shaped ones, in the shapes their formats accept — see
    # test_the_stage_three_designer_boxes_refuse_what_the_designer_page_refuses in
    # test_stage_schema.py for what happens to the shapes that are not accepted.
    "phone": "+91 9876500001",
    "email": "latha.nayak@nift.ac.in",
    "website": "https://nift.ac.in/bhubaneswar",
    "addressLine": "Plot 3, IDCO Institutional Area, Chandaka",
    "city": "Bhubaneswar",
    "state": "Odisha",
    "pincode": "751024",
    "photoMediaId": "cm000000000000000000photo",
    "signatureMediaId": "cm00000000000000000000sig",
    "cvMediaId": "cm0000000000000000000000cv",
    "empanelmentNo": "DCH/EMP/2024/0117",
    # THE SUBJECT OF THIS SECTION. Written as an aware datetime because that is what Prisma hands
    # back for a DateTime column, and with a time on it because a midnight-only fixture would pass
    # against a narrowing that silently dropped to UTC-midnight and against one that did not.
    "empanelmentDate": datetime(2026, 3, 14, 18, 45, 12, tzinfo=UTC),
}


def _stub_profile() -> SimpleNamespace:
    """A profile row with every column in ``PROFILE_FIELDS`` answered, plus its ``user`` relation.

    ``getattr(profile, column, None)`` is how ``prefill_from_profile`` reads each column, so a
    ``SimpleNamespace`` is a faithful stand-in — and the assertion below that no column is missing
    from ``_PROFILE_VALUES`` is what stops this fixture from quietly under-testing a new column by
    handing the loop a ``None`` it reads as "the designer has not filled this in".
    """
    missing = sorted(set(PROFILE_FIELDS) - set(_PROFILE_VALUES))
    assert missing == [], (
        f"_PROFILE_VALUES has no answer for {missing}; prefill_from_profile would skip those "
        "columns as unfilled and the round-trip below would silently stop covering them"
    )
    return SimpleNamespace(
        **_PROFILE_VALUES,
        user=SimpleNamespace(name="Latha Nayak", email="latha.nayak@nift.ac.in"),
    )


async def test_a_datetime_column_reaches_a_date_field_as_a_plain_yyyy_mm_dd(monkeypatch):
    """The narrowing is a narrowing, and not the first ten characters of a repr.

    THE DEFECT THIS PREVENTS is not a crash — it is the absence of one. Without
    ``.date().isoformat()`` the value handed to ``coerce_value`` is a ``datetime`` OBJECT, and DATE's
    arm reads ``str(raw).strip()[:10]``, which answers "2026-03-14" for it. So the wrong thing works,
    by coincidence, and keeps working right up until the coincidence stops holding: a Prisma upgrade
    that changes ``datetime.__str__``, a column that comes back naive so the repr shortens, or the
    second date column somebody adds to this table whose repr is not ISO-ordered at all. At that
    point the empanelment date in a submitted report is a truncated repr and nothing raised.

    THREE THINGS ARE ASSERTED, and the type is the one that matters most: a ``str``, whose value is
    exactly ``yyyy-mm-dd``, and which ``coerce_value`` accepts UNCHANGED. Unchanged is the real
    claim — a value the coercion has to repair is a value that arrived in the wrong shape.

    THE TIME IS NOT MIDNIGHT IN THE FIXTURE, deliberately. ``18:45:12`` is dropped by ``.date()``,
    which is the intended loss (a registry DATE has no time to print), and a fixture at midnight
    would pass equally well against a narrowing that never ran.
    """
    monkeypatch.setattr(designers, "db", _StubDb(_stub_profile()))

    values = await designers.prefill_from_profile("user-latha")

    stored_date = values["designerEmpanelmentDate"]
    assert isinstance(stored_date, str), (
        f"designerEmpanelmentDate is a {type(stored_date).__name__}, so what reaches the JSON "
        "column depends on datetime.__str__ rather than on this function"
    )
    assert stored_date == "2026-03-14", stored_date

    field = _designer_field("designerEmpanelmentDate")
    assert field.type is FieldType.DATE
    assert coerce_value(field, stored_date) == ("2026-03-14", None), (
        "the narrowed value is not what the registry field stores, so the prefill and every client "
        "send this box two different shapes"
    )

    # The lookup is still by the profile's unique key. A prefill keyed on anything a second account
    # can share would seed one designer's institution, phone number and signature into another
    # designer's report.
    assert designers.db.designerprofile.calls == [({"userId": "user-latha"}, {"user": True})]


async def test_every_prefilled_value_survives_the_coercion_that_seeds_it(monkeypatch):
    """The whole prefill, through the real registry entities, exactly as ``_seed_prefill`` does it.

    WHY THIS IS THE TEST THE DATE ONE IS A SPECIAL CASE OF. ``_seed_prefill`` does not write what
    ``prefill_from_profile`` returns; it writes ``validate_entry(entity, subset,
    enforce_required=False)[0]``. So a value of the wrong SHAPE for its target field is not a 422 —
    it is a WARNING in the server log and nothing the designer ever sees.
    ``coerce_value`` answers ``(None, "...")``, ``validate_entry`` drops the key, and the
    box is simply empty on a stage the designer never opened, with the Designer Page still showing
    the value it promised to copy. That is the same invisible-loss failure as a mistyped key, arrived
    at from the other end, and the map tests above cannot see it because the KEY is perfectly valid.

    The map grew from four pairs to twenty-one on 2026-08-25, so nineteen of these targets have never
    had their shape checked against anything. Two are DATE/INT and three carry a declared format;
    the rest are bounded text, and ``max_length`` is a real refusal — ``email`` is unbounded on the
    profile body and 180 on ``designerEmail``, so an address the Designer Page accepts is one the
    stage silently drops. (``qualification`` and ``specialisation`` stood here until 2026-08-26,
    when they were measured: both are 220 on BOTH sides, which is exactly why neither appears in
    ``KNOWN_PREFILL_GAPS`` below — a divergence named in prose and absent from that dict is the
    contradiction this file exists to prevent.)
    """
    monkeypatch.setattr(designers, "db", _StubDb(_stub_profile()))
    values = await designers.prefill_from_profile("user-latha")

    # Every pair in the map answered, or the sweep below is measuring a subset of the feature.
    assert set(values) == {field_key for _column, field_key in PREFILL_MAP}, (
        "the prefill did not answer every PREFILL_MAP pair; a column read as empty here would be "
        "skipped by the loop below rather than checked"
    )

    seeded: dict[str, object] = {}
    for stage in STAGES:
        for entity in stage.entities:
            if entity.cardinality is not Cardinality.SINGLETON:
                continue
            # `known` and the SINGLETON filter are copied from `_seed_prefill` rather than
            # reinvented, deprecated fields excluded exactly as it excludes them. A test that
            # computed the subset its own way would be checking a path the app does not take.
            known = {f.key for f in entity.fields if not f.deprecated}
            subset = {k: v for k, v in values.items() if k in known}
            if not subset:
                continue
            clean, errors = validate_entry(entity, subset, enforce_required=False)
            assert errors == {}, (
                f"{stage.key}/{entity.key} refused prefilled values: {errors}. _seed_prefill drops "
                "a refused key silently, so the box would be blank on a stage nobody has opened "
                "while the Designer Page still shows the value it promised to copy."
            )
            dropped = sorted(set(subset) - set(clean))
            assert dropped == [], (
                f"{stage.key}/{entity.key} dropped {dropped} with no error recorded — the quietest "
                "form of the same loss"
            )
            seeded.update(clean)

    # AND EVERY VALUE LANDED SOMEWHERE. A key that matched no singleton entity is a value
    # `_seed_prefill` computes, carries and then never writes: the loop only visits SINGLETONs, so a
    # designer field moved onto a COLLECTION entity would vanish here with nothing to read.
    unseeded = sorted(set(values) - set(seeded))
    assert unseeded == [], (
        f"{unseeded} were prefilled but land on no singleton entity, so _seed_prefill never writes "
        "them: the loop it runs skips COLLECTION entities"
    )


# --------------------------------------------------------------------------------------
# THE OTHER END OF THE SAME PIPE: what the profile lets IN versus what the stage lets THROUGH
#
# The tests above prove the map joins up and that the values it produces survive coercion. Both
# start from a profile whose columns are well-formed. This section starts from the opposite end,
# because that is where the loss actually happens, and the mechanism is worth stating in full:
#
#   `_seed_prefill` calls ``validate_entry(entity, subset, enforce_required=False)`` and then
#   writes ``clean``. A key ``coerce_value`` refused is simply absent from ``clean``.
#
#   IT NO LONGER DISCARDS THE ERRORS. Until 2026-08-26 the call site read ``clean, _errors = ...``
#   and this paragraph said so; it now reads ``clean, refused = ...`` and emits a WARNING naming the
#   entity, every refused key and its reason. That makes the loss TRACEABLE — in the server log, by
#   somebody who already suspects it — and changes nothing about what the designer sees.
#
# So a profile column the Designer Page accepted and a stage box refuses does not 422 and does not
# reach any surface a person is looking at. Stage 3's box is empty on a workshop nobody has opened, every
# report generated from it is missing that line, and the Designer Page goes on displaying the value
# it promised to copy. The designer's only signal is the absence of one, which is this repository's
# most repeated bug class arriving through the prefill.
#
# ``DesignerProfileUpdate`` already knows this rule and already applies it once, on the INT:
# "Bounded exactly as the registry's ``designerExperience`` field is (min 0, max 70), because this
# value is COPIED into that field when a workshop is created. A profile that accepted 400 years
# would prefill a stage the stage's own validator then rejects." Seventeen of the twenty-one pairs
# hold to that. Three do not, and all three are the boxes that gained a ``text_format`` on
# 2026-08-25 — so the wave that closed the gap on the REPORT side widened it on the PROFILE side.
# They are listed below rather than silently tolerated.
# --------------------------------------------------------------------------------------

#: WHERE THE PROFILE IS LOOSER THAN THE STAGE BOX IT SEEDS. Measured on 2026-08-26, on this tree.
#:
#: NOT A TODO LIST AND NOT AN APOLOGY — it is the same device as ``PREFILL_EXEMPT`` above,
#: ``server_only`` in the shared vector table and ``divergences_to_reconcile`` beside it: a known
#: divergence written down in one place, with its consequence, so that the eighteenth pair cannot
#: join it unnoticed. The two tests below use it in BOTH directions, which is what stops an entry
#: outliving the gap it describes: each one must still be a real gap, or the test fails and asks
#: whoever closed it to delete the line.
#:
#: FIXING ANY OF THE THREE IS A ONE-LINE CHANGE IN ``app/schemas/designers.py`` and is an owner call
#: rather than a test's, because narrowing a body that is already in production refuses saves that
#: currently succeed — a designer whose stored phone is "office landline, ask for Latha" would meet
#: a 422 on the next profile save, over a value they can no longer see the point of. Whoever makes
#: it should also decide what happens to the rows already holding such a value.
KNOWN_PREFILL_GAPS: dict[str, str] = {
    # 40 characters and no format check, seeding a box bounded at 20 that declares PHONE_IN. Both
    # halves bite: "please call the office" (22 chars) is accepted by the body and refused by the
    # length, and "987650000" (nine digits) is accepted by the body and refused by the format.
    "phone": "no bound below designerPhone's 20 and no PHONE_IN check",
    # `EmailStr` with NO `max_length` at all, seeding a box bounded at 180. The SHAPE is fine —
    # EmailStr refuses "latha@example" exactly as `email_error` does — so this is purely the bound,
    # and the bound is the half `participant.email` was already caught on ("a format is a shape,
    # not a length, and this field had NO length at all").
    "email": "unbounded, while designerEmail is bounded at 180",
    # Same length on both sides (12), so no length gap — but no PINCODE check on the way in, so
    # "12345" and "068029" both save on the Designer Page — whose box strips non-digits and caps at
    # six on both clients (DesignerProfileForm.tsx:440, DesignerProfileScreen.kt:760), so a LETTER
    # can only arrive through a direct API call — and both are refused by the
    # stage box and dropped. `address.validate_pincode` exists for exactly this use and its own
    # docstring says so: "raises ValueError for a Pydantic field validator".
    "pincode": "no PINCODE check, while designerPincode declares one",
}


def _body_max_length(column: str) -> int:
    """The ``max_length`` ``DesignerProfileUpdate`` declares for one column, or 0 for unbounded.

    Read off the model rather than off a copied table, and read out of ``metadata`` because that is
    where Pydantic v2 keeps an ``annotated_types.MaxLen`` — ``FieldInfo`` has no ``max_length``
    attribute of its own, so the obvious spelling would answer ``None`` for every field and make
    every comparison below vacuously true.
    """
    info = DesignerProfileUpdate.model_fields[column]
    for constraint in info.metadata:
        bound = getattr(constraint, "max_length", None)
        if bound is not None:
            return int(bound)
    return 0


def test_no_profile_column_is_bounded_wider_than_the_stage_box_it_seeds():
    """A profile that accepts more than the report box can hold loses the difference in silence.

    THE DEFECT, once more in the direction this test reads: the designer types 190 characters of
    email address, the Designer Page saves it and shows it back, and every workshop created
    afterwards has an empty designer-email line because ``coerce_value`` refused 190 against a bound
    of 180 and ``_seed_prefill`` dropped the key. No 422, no log, no red box — the value is simply
    not in the report.

    ONLY SCALAR TEXT TARGETS ARE COMPARED. A ``max_length`` of 0 on the registry side means
    unbounded, which cannot be narrower than anything; RICH_TEXT, DATE, INT, IMAGE and FILE targets
    do not read ``max_length`` in their arm of ``coerce_value`` at all, so a bound on the profile
    side of those pairs is a bound with nothing to disagree with.
    """
    stage_fields = {}
    for stage in STAGES:
        for entity in stage.entities:
            for field in entity.fields:
                stage_fields.setdefault(field.key, field)

    wider = {}
    for column, field_key in PREFILL_MAP:
        field = stage_fields[field_key]
        if not field.max_length:
            continue
        body_bound = _body_max_length(column)
        # An unbounded body (0) is the widest of all and must be reported, not skipped — that is
        # exactly what `email` is.
        if body_bound == 0 or body_bound > field.max_length:
            wider[column] = (body_bound, field_key, field.max_length)

    unexpected = sorted(set(wider) - set(KNOWN_PREFILL_GAPS))
    assert unexpected == [], (
        "these profile columns accept more than the stage box they are copied into: "
        + ", ".join(
            f"{c} (body {wider[c][0] or 'unbounded'} -> {wider[c][1]} {wider[c][2]})"
            for c in unexpected
        )
        + ". _seed_prefill discards validate_entry's errors, so the over-long value is dropped and "
        "the report line is blank while the Designer Page still shows it. Narrow the body in "
        "app/schemas/designers.py — the way experienceYears already is — or add the column to "
        "KNOWN_PREFILL_GAPS with what it costs."
    )


def test_the_recorded_prefill_gaps_are_all_still_real():
    """An allow-list entry that outlives its gap is a false statement in the code.

    The same argument, and the same shape, as
    ``test_the_exemption_list_cannot_name_a_column_that_is_also_carried`` above. ``KNOWN_PREFILL_GAPS``
    exists so that three measured divergences are visible rather than tolerated; the moment one is
    closed in ``app/schemas/designers.py``, the line describing it becomes a note telling the next
    reader that a fixed thing is broken. So each entry must still demonstrate itself, with a value
    the profile body accepts and the stage box refuses — which is also the clearest possible record
    of what each gap actually costs.

    A REFUSAL FROM THE STAGE SIDE IS ASSERTED TOO, not only acceptance from the body side. If the
    stage box ever stopped refusing these values — its format deleted, its bound widened — the pair
    would agree again and the gap would be gone for the opposite reason, and an assertion that
    checked only the body would go on claiming a divergence that no longer exists.
    """
    from pydantic import ValidationError

    # One value per gap that the Designer Page accepts and the report box refuses. Chosen to
    # exercise the specific half named in KNOWN_PREFILL_GAPS rather than any refusal at all.
    demonstrations = {
        "phone": ("designerPhone", "987650000"),          # nine digits: the missing PHONE_IN check
        "email": ("designerEmail", "l" * 190 + "@nift.ac.in"),   # 201 chars: the missing bound
        "pincode": ("designerPincode", "12345"),          # five digits: the missing PINCODE check
    }
    assert set(demonstrations) == set(KNOWN_PREFILL_GAPS), (
        "every recorded gap needs a demonstration value, or the entry is an unverified claim"
    )

    for column, (field_key, value) in demonstrations.items():
        try:
            DesignerProfileUpdate(**{column: value})
        except ValidationError as exc:   # pragma: no cover - the day this fires is the fix landing
            raise AssertionError(
                f"DesignerProfileUpdate now refuses {column}={value!r} ({exc.error_count()} "
                f"error(s)), so the gap recorded in KNOWN_PREFILL_GAPS[{column!r}] is closed. "
                "Delete that entry."
            ) from exc

        stored, error = coerce_value(_designer_field(field_key), value)
        assert error is not None and stored is None, (
            f"{field_key} now accepts {value!r}, so the profile and the stage agree again and "
            f"KNOWN_PREFILL_GAPS[{column!r}] is stale — but check WHICH side moved before deleting "
            "it: a bound widened or a format deleted on the stage side closes the gap by giving up "
            "the check, not by gaining one."
        )


def test_each_prefilled_key_is_answered_by_exactly_one_singleton_entity():
    """A flattened key with two receivers would seed a box nobody chose, and nothing would say so.

    THE INVARIANT ``_seed_prefill`` DEPENDS ON WITHOUT STATING. It walks every SINGLETON entity of
    every stage, asks ``known = {f.key for f in entity.fields if not f.deprecated}``, and writes the
    intersection. That is deliberately registry-driven — its own comment explains that hard-coding
    "WORKSHOP_SETUP"/"workshopPlan" would break silently the day the designer block moves — but the
    consequence is that a key appearing on TWO singletons is written to BOTH, and ``PREFILL_MAP``'s
    right-hand side is a bare field key with no entity beside it. There is nowhere to express which
    one was meant.

    THIS IS NOT HYPOTHETICAL AND THE NEAR MISS IS THE POINT. ``designerName`` is declared TWICE in
    this registry: on stage 1's ``workshopSetup`` (the cover's "Designer") and on stage 16's
    ``finalProduct`` as "Designed by" — a per-product credit line, a different question with the
    same key. It is safe today for one reason and one only: ``finalProduct`` is a COLLECTION, and
    ``_seed_prefill``'s loop skips anything that is not a SINGLETON. Nothing anywhere records that
    the safety rests on a cardinality, so a later edit promoting that entity, or a new singleton
    reusing a designer key, would silently stamp the designer's own name into every product's credit
    line on a stage they have not opened — and the value would look deliberate.

    THE MAP GREW FROM FOUR KEYS TO TWENTY-ONE ON 2026-08-25, which is what makes this worth a test
    rather than a comment: seventeen new keys is seventeen new chances of a collision, and
    ``validate_registry`` permits a duplicate field key across entities on purpose (keys are unique
    per entity, not globally — ``turntablePhotos`` and ``recordPincode`` are each declared three
    times, correctly).

    COLLECTION HITS ARE REPORTED SEPARATELY AND NOT AS FAILURES, because a collection receiver is
    the near miss rather than the defect. The assertion is on singletons, which is exactly the set
    ``_seed_prefill`` writes to.
    """
    prefilled = {field_key for _column, field_key in PREFILL_MAP}

    singleton_receivers: dict[str, list[str]] = {key: [] for key in prefilled}
    collection_receivers: dict[str, list[str]] = {key: [] for key in prefilled}
    for stage in STAGES:
        for entity in stage.entities:
            bucket = (singleton_receivers
                      if entity.cardinality is Cardinality.SINGLETON
                      else collection_receivers)
            for field in entity.fields:
                if field.key in prefilled and not field.deprecated:
                    bucket[field.key].append(f"stage {stage.number}/{entity.key}")

    ambiguous = {k: v for k, v in singleton_receivers.items() if len(v) != 1}
    assert ambiguous == {}, (
        "these prefilled keys do not resolve to exactly one singleton entity: "
        + "; ".join(f"{k} -> {v or 'nothing'}" for k, v in sorted(ambiguous.items()))
        + ". _seed_prefill writes the key into EVERY singleton that declares it, and PREFILL_MAP "
        "has no way to say which one was meant — so the designer's value lands in a box nobody "
        "chose, on a stage they have not opened, looking deliberate."
    )

    # The near miss, asserted as the near miss it is. If this stops being a collection, the
    # assertion above stops protecting anything and the docstring's reasoning has to be re-read.
    assert collection_receivers["designerName"] == ["stage 16/finalProduct"], (
        "stage 16's 'Designed by' box has moved or changed cardinality: "
        f"{collection_receivers['designerName']}. Re-read this test's docstring — the safety of "
        "designerName's duplicate key rests entirely on finalProduct being a COLLECTION."
    )

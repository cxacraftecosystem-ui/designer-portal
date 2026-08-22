"""Retracting PII must not write the retracted value into the audit ledger.

THE DEFECT THIS PINS. Clearing a nullable scalar became possible when the per-model ``clearable``
argument landed, so a researcher told "take my number off your system" could finally do it. But
``access.record_revision`` diffs the payload against the row and stores ``{old, new}`` verbatim for
every key not in ``REVISION_SKIP_FIELDS`` — and that set holds only infrastructural churn
(extraMetadata, location, timestamps, createdById). So the request that emptied ``Artisan.phone``
COPIED the number into an immutable ``RecordRevision`` on its way out, and answered 200. The column
was NULL, the app said "saved", and the number had been duplicated into the one table with no delete
endpoint and no subject-facing view.

That is a worse position than the one before clearing worked at all. Then the value sat in exactly
one place and everybody knew which; after, the product asserts a retraction it did not perform.

WHAT THE FIX IS NOT, AND THIS FILE PINS BOTH HALVES. The one-line version — drop the contact columns
into ``REVISION_SKIP_FIELDS`` — trades a privacy bug for an audit hole, and on a GOVERNMENT record
that is not obviously the better trade: an EDIT-tier grantee could then overwrite an artisan's phone
number with their own and leave the ledger completely empty, on the one surface
(``GET /api/data-access/revisions``) an admin has for reconstructing who did what. So the tests below
come in pairs. Every "the value did not cross" assertion has a matching "the change was still
recorded, with its author" assertion beside it, and a future edit that satisfies one by sacrificing
the other fails here rather than in a ministry's copy of a report.

AND THE READ END, BECAUSE HALF OF THIS DESIGN LIVES THERE. Everything above the "the entry SURVIVES
the wire" divider pins what ``record_revision`` WRITES; everything below it drives the real
``records.public_encode`` over that blob, which is what ``routes/data_access.list_revisions`` serves
it through. The redaction had already cancelled itself out there once — see the divider — and the
same walk meets two other shapes keyed by column name (a ``fieldProvenance`` stamp, and whatever a
client wrote into ``extraMetadata``), so those are pinned here too rather than in a file about
masking, because they are only reachable BECAUSE of the wording chosen above.

NO DATABASE. What is under test is which bytes ``record_revision`` puts in the ``changes`` blob and
which bytes come back out of the encode, so the Prisma delegate is a recorder and the whole file runs
in well under a second. The round-trip behaviour of the table itself is already covered by
``tests/test_process_refusal_leaves_no_revision.py``, which does need Postgres.
"""

import asyncio
from types import SimpleNamespace

import pytest

from app.services import access
from app.services.access import REVISION_REDACTED_FIELDS, REVISION_SKIP_FIELDS, record_revision

# The artisan as the researcher first recorded her: every redacted column populated, so each test can
# retract, replace or read past whichever one it is about.
_POPULATED = {
    "id": "artisan-1",
    "name": "Kamla Devi",
    "phone": "9876543210",
    "email": "kamla@example.test",
    "address": "House 12, Potters' Lane, Bagru",
    "aadhaarNumber": "123456789012",
    "pehchanCardNumber": "PMVY12345678",
    "notes": "Met at the Bagru fair.",
    "createdById": "researcher-1",
}

# Every literal that must never appear in the ledger, in one place so a test can assert against the
# whole set rather than against the single column it happens to be editing.
_SECRETS = (
    "9876543210",
    "kamla@example.test",
    "House 12, Potters' Lane, Bagru",
    "123456789012",
    "PMVY12345678",
)


class _Recorder:
    """Stands in for ``db.recordrevision``. Keeps what it was asked to create, and nothing else."""

    def __init__(self) -> None:
        self.created: list[dict] = []

    async def create(self, data: dict) -> SimpleNamespace:
        self.created.append(data)
        return SimpleNamespace(id="revision-1")


@pytest.fixture
def ledger(monkeypatch: pytest.MonkeyPatch) -> _Recorder:
    recorder = _Recorder()
    monkeypatch.setattr(access, "db", SimpleNamespace(recordrevision=recorder))
    return recorder


def _revise(ledger: _Recorder, payload: dict, record: dict | None = None) -> dict | None:
    """Run the real ``record_revision`` over one edit and hand back the ``changes`` blob it wrote.

    Returns None when it wrote no row at all, so "nothing was logged" and "something was logged with
    an empty blob" cannot be confused for one another by a caller that only looked at the fields.
    """
    artisan = SimpleNamespace(**{**_POPULATED, **(record or {})})
    editor = SimpleNamespace(id="editor-7", name="Second Researcher")
    asyncio.run(record_revision(artisan, editor, payload, "artisan"))
    if not ledger.created:
        return None
    return ledger.created[-1]["changes"].data


def _blob(changes: dict) -> str:
    """The whole entry as one string, for asserting a literal is nowhere inside it at any nesting."""
    return repr(changes)


# ---------------------------------------------------------------- the value does not cross


def test_clearing_a_phone_number_does_not_copy_it_into_the_ledger(ledger) -> None:
    """THE BUG. The researcher was told to delete the number; the ledger must not keep it for them."""
    changes = _revise(ledger, {"phone": None})

    assert changes is not None, "the edit must still be audited"
    assert "9876543210" not in _blob(changes)


def test_no_redacted_column_leaks_its_value_on_retraction(ledger) -> None:
    """All five columns, cleared one at a time, against every secret the record holds.

    Written against the whole ``_SECRETS`` tuple rather than the one column under edit so that a
    future entry added to the set with a half-done implementation cannot pass by leaking a
    NEIGHBOURING field's value.
    """
    for field in sorted(REVISION_REDACTED_FIELDS):
        ledger.created.clear()
        changes = _revise(ledger, {field: None})

        assert changes is not None, f"clearing {field} must still be audited"
        assert field in changes, f"clearing {field} must name the field"
        text = _blob(changes)
        for secret in _SECRETS:
            assert secret not in text, f"clearing {field} leaked {secret!r}"


def test_replacing_one_number_with_another_leaks_neither(ledger) -> None:
    """A correction is not a retraction, but the old value is just as much the subject's as before —
    and the NEW one must not be banked in the ledger either, or the next retraction is defeated by
    the edit that preceded it."""
    changes = _revise(ledger, {"phone": "9000000001"})

    assert "9876543210" not in _blob(changes)
    assert "9000000001" not in _blob(changes)


def test_filling_an_empty_column_does_not_store_the_new_value(ledger) -> None:
    changes = _revise(ledger, {"phone": "9000000001"}, record={"phone": None})

    assert "9000000001" not in _blob(changes)


# ---------------------------------------------------------------- the audit trail survives it


def test_a_retraction_still_records_which_field_who_and_when(ledger) -> None:
    """The half a blanket skip-list exemption would have thrown away.

    WHO and WHEN are columns on the row, not keys in the blob: ``editedById`` is written here and
    ``createdAt`` is defaulted by the database. Asserting on ``editedById`` is what stops a later
    "just skip these fields" simplification from passing this file.
    """
    _revise(ledger, {"phone": None})

    row = ledger.created[-1]
    assert row["editedById"] == "editor-7"
    assert row["recordType"] == "artisan"
    assert row["recordId"] == "artisan-1"
    assert "phone" in row["changes"].data


def test_a_silent_contact_edit_is_impossible(ledger) -> None:
    """The malicious case the privacy fix must not enable: a grantee quietly repointing an artisan's
    phone number at themselves. It has to leave a row naming them."""
    changes = _revise(ledger, {"phone": "9000000001"})

    assert changes is not None
    assert "phone" in changes
    assert ledger.created[-1]["editedById"] == "editor-7"


def test_the_direction_of_the_change_is_legible(ledger) -> None:
    """Set, replaced and cleared are three different events and an admin has to be able to tell them
    apart without the value. They are also asserted to be UNEQUAL in each case: the only reader,
    ``CollabPanel.tsx``, draws an ``old -> new`` line, and a placeholder repeated on both sides would
    render as though nothing had happened."""
    cleared = _revise(ledger, {"phone": None})["phone"]
    assert cleared["new"] == "(cleared)"
    assert cleared["old"] != cleared["new"]

    replaced = _revise(ledger, {"phone": "9000000001"})["phone"]
    assert replaced["new"] == "(value replaced)"
    assert replaced["old"] != replaced["new"]

    filled = _revise(ledger, {"phone": "9000000001"}, record={"phone": None})["phone"]
    assert filled["old"] == "(empty)"
    assert filled["old"] != filled["new"]


def test_re_clearing_an_already_empty_column_does_not_claim_a_retraction(ledger) -> None:
    """The fourth branch, which the first thirteen tests here did not reach.

    ``deps.values_match(None, "")`` is False — ``None == ""`` is False, ``Decimal("None")`` raises,
    and the string fallback compares ``"None"`` against ``""`` — so a payload sending ``phone: ""``
    against a column that is already NULL DOES reach ``_redacted_change``. It must not answer
    "(cleared)": a ledger row asserting a retraction on a column that held nothing is a false
    positive on the one screen an admin opens to learn what was done to a record.
    """
    changes = _revise(ledger, {"phone": ""}, record={"phone": None})

    assert changes is not None, "the churn is logged the same way every other column's is"
    assert changes["phone"]["old"] == "(empty)"
    assert changes["phone"]["new"] == "(still empty)"
    assert changes["phone"]["old"] != changes["phone"]["new"]
    assert changes["phone"]["redacted"] is True


def test_the_entry_is_marked_redacted_so_a_reader_can_tell(ledger) -> None:
    """A placeholder that cannot be distinguished from a stored value is a trap for the next reader
    of this table — including a human one reading an address that happens to read like a marker."""
    changes = _revise(ledger, {"phone": None})

    assert changes["phone"]["redacted"] is True


# ---------------------------------------------------------------- the redaction stays narrow


def test_an_ordinary_field_still_records_its_real_values(ledger) -> None:
    """The redaction must not creep into a blanket. ``notes`` is where a malicious edit hides
    meaning, and the old text is the only way to see what was taken out of a record."""
    changes = _revise(ledger, {"notes": "Nothing to report."})

    assert changes["notes"]["old"] == "Met at the Bagru fair."
    assert changes["notes"]["new"] == "Nothing to report."
    assert "redacted" not in changes["notes"]


def test_one_payload_redacts_only_the_redacted_half(ledger) -> None:
    """The realistic shape: a researcher fixes a note and drops the phone number in one save."""
    changes = _revise(ledger, {"phone": None, "notes": "Nothing to report."})

    assert changes["notes"]["new"] == "Nothing to report."
    assert "9876543210" not in _blob(changes)


def test_nothing_meaningful_changed_still_writes_no_row(ledger) -> None:
    """``record_revision``'s own contract, re-pinned because the redaction branch sits inside the
    loop that enforces it: re-posting the same phone number is not an edit."""
    assert _revise(ledger, {"phone": "9876543210"}) is None


def test_the_two_sets_do_not_overlap() -> None:
    """A column in both sets would be silently unreachable: ``REVISION_SKIP_FIELDS`` is tested first,
    so the redaction branch would never run and the field would vanish from the ledger entirely —
    which is the audit hole the redaction exists to avoid."""
    assert not (REVISION_REDACTED_FIELDS & REVISION_SKIP_FIELDS)


def test_the_redacted_set_is_the_five_columns_the_comment_argues_for() -> None:
    """Pinned by name so that widening it is a deliberate edit that updates the argument above the
    set, rather than something a later patch does in passing. ``dateOfBirth``, ``notes``, ``dos``,
    ``donts`` and ``localName`` are named there as considered and deliberately excluded."""
    assert sorted(REVISION_REDACTED_FIELDS) == [
        "aadhaarNumber",
        "address",
        "email",
        "pehchanCardNumber",
        "phone",
    ]


# ------------------------------------------------- the entry SURVIVES the wire, or the fix is moot
#
# Everything above pins what ``record_revision`` WRITES. None of it looks at what a reader gets, and
# the reader is where the value of this whole design lives: the point of "(value recorded)" ->
# "(cleared)" instead of a null pair is that ``CollabPanel`` prints a legible before/after line
# rather than "— → —" on the one screen an admin opens to find out that something was done. That
# promise held right up to ``public_encode``, which every response goes through and which masks
# ``aadhaarNumber``/``pehchanCardNumber`` BY KEY NAME. ``changes`` is keyed by column name too, so the
# blob's ``aadhaarNumber`` entry — a dict — was handed to a masker that normalises with
# ``str(value)``, and ``GET /api/data-access/revisions`` served the literal string
# ``"XXXX XXXX rue}"``: the tail of ``…'redacted': True}`` with the spaces stripped. A string has no
# ``.old``, so the row rendered as "— → —" and the redaction had quietly cancelled itself out on the
# only surface that reads it.
#
# These tests drive the real ``public_encode`` with NO viewer, which is exactly what
# ``routes/data_access.list_revisions`` passes.


def _served(changes: dict) -> dict:
    """One revision row as the API hands it out: no viewer named, so everything is masked."""
    from app.services.records import public_encode

    return public_encode([{"id": "revision-1", "changes": changes}])[0]["changes"]


def test_a_redacted_entry_reaches_the_reader_with_its_shape_intact(ledger) -> None:
    """THE DEFECT, PINNED AT THE READER. Both identity columns, because both are masked by name and
    neither was surviving the encode."""
    changes = _revise(ledger, {"aadhaarNumber": None, "pehchanCardNumber": None})

    served = _served(changes)

    for column in ("aadhaarNumber", "pehchanCardNumber"):
        assert isinstance(served[column], dict), (
            f"{column}: the audit entry was flattened into {served[column]!r} on the way out. A "
            "reader doing `change.old` gets undefined and prints the row as nothing having happened."
        )
        assert served[column]["old"] == "(value recorded)"
        assert served[column]["new"] == "(cleared)"
        assert served[column]["redacted"] is True


def test_the_contact_columns_were_never_affected_and_still_are_not(ledger) -> None:
    """The other half of the same blob, so a fix aimed at the two identity keys cannot have been
    bought by changing what the walk does to the three columns it never touched."""
    changes = _revise(ledger, {"phone": None, "email": None, "address": None})

    served = _served(changes)

    for column in ("phone", "email", "address"):
        assert served[column] == {
            "old": "(value recorded)",
            "new": "(cleared)",
            "redacted": True,
        }


def test_an_ordinary_column_in_the_same_blob_still_carries_its_values(ledger) -> None:
    """``notes`` is deliberately NOT redacted — the old text is how a reader sees what was quietly
    taken out of a record — and the encode must not start touching it either."""
    changes = _revise(ledger, {"notes": None, "aadhaarNumber": None})

    served = _served(changes)

    assert served["notes"] == {"old": "Met at the Bagru fair.", "new": None}


def test_a_historical_row_written_before_the_redaction_shows_no_digits() -> None:
    """THE ROWS THIS FIX MUST NOT WIDEN, and the reason the nested entry is re-derived not masked.

    Rows written before ``REVISION_REDACTED_FIELDS`` existed still hold real retracted numbers in
    the blob — a migration over a government audit table is an owner decision, not something a
    rendering fix performs. The flattening bug served a caller nothing of those numbers, and this
    keeps it that way: the entry becomes legible (a dict a reader can render) without a single digit
    of the stored value appearing that was not visible before.

    It is served with the SAME WORDING a row written today gets, rather than a pair of masks. Two
    masks would read "XXXX XXXX XXXX → XXXX XXXX XXXX" for a REPLACEMENT (see the test below), which
    is the "nothing happened" reading the four distinct wordings exist to prevent — and there is no
    reason for the edit-history screen to tell an admin which rows predate the redaction and nothing
    else useful.
    """
    served = _served({"aadhaarNumber": {"old": "123456789012", "new": None}})

    assert isinstance(served["aadhaarNumber"], dict)
    assert "9012" not in repr(served["aadhaarNumber"]), (
        "a historical raw number must not gain a last-four mask as a side effect of the shape fix — "
        "widening that is the owner's call, spelled out in `records._mask_identity_node`"
    )
    assert served["aadhaarNumber"] == {
        "old": "(value recorded)",
        "new": "(cleared)",
        "redacted": True,
    }


def test_a_historical_replacement_row_does_not_read_as_nothing_having_changed() -> None:
    """THE BRANCH A TOTAL MASK GETS WRONG. ``{"old": "1111…", "new": "4444…"}`` is a REPLACEMENT — an
    editor repointing an artisan at a different Aadhaar, which is the single most important thing
    this ledger records — and masking both sides to the same string serves
    "XXXX XXXX XXXX → XXXX XXXX XXXX": legible, and reading as though nothing changed. Both readers
    (``CollabPanel``, Android's ``RecordCollabSection``) print the pair verbatim, so the two sides
    have to differ.
    """
    served = _served({"aadhaarNumber": {"old": "111122223333", "new": "444455556666"}})

    assert served["aadhaarNumber"]["old"] != served["aadhaarNumber"]["new"], (
        "a replacement served with the same placeholder on both sides reads as no change at all"
    )
    assert served["aadhaarNumber"] == {
        "old": "(value recorded)",
        "new": "(value replaced)",
        "redacted": True,
    }
    for secret in ("1111", "2222", "3333", "4444", "5555", "6666"):
        assert secret not in repr(served["aadhaarNumber"])


# ---------------------------------------------- the client-writable blob under the same key names
#
# ``changes`` is not the only thing keyed by column name. ``extraMetadata`` is a client-writable Json
# column: ``merge_field_provenance`` merges the request body's copy into the stored one and
# ``public_encode`` echoes the result whole, so a caller can put ANY shape under ``aadhaarNumber``
# inside it and have the identity walk meet it. The first version of the container walk replaced only
# ``str`` leaves and returned any dict carrying ``redacted: True`` untouched, which handed both of
# these straight back:
#     {"redacted": True, "note": "123456789012"}   ->  echoed verbatim
#     {"old": 987654321098}                        ->  echoed verbatim (an int is not a str)
# Twelve digits, out of the function whose entire job is that twelve digits do not travel. These pin
# the blunt rule that replaced it: a container this function does not RECOGNISE is masked whole.


_MASKED_WHOLE = "XXXX XXXX XXXX"
_RETRACTION = {"old": "(value recorded)", "new": "(cleared)", "redacted": True}


@pytest.mark.parametrize(
    ("blob", "expected"),
    [
        # A forged flag beside a real value: the flag is a rendering convenience, never a permission.
        ({"redacted": True, "note": "123456789012"}, _MASKED_WHOLE),
        # Audit-SHAPED, so it is re-derived rather than masked. That a client wrote it changes
        # nothing: re-deriving reads only whether each side was empty, so the value cannot survive.
        ({"old": 987654321098}, _RETRACTION),
        # One key outside {old, new, redacted} and it is not an audit entry any more: masked whole.
        ({"old": 987654321098, "extra": [123456789012, 5.5]}, _MASKED_WHOLE),
        (["123456789012"], _MASKED_WHOLE),
        # Digits hidden in a dict KEY, which a leaf-by-leaf walk preserves and a whole mask does not.
        ({"123456789012": "hidden in the key"}, _MASKED_WHOLE),
        (
            {"redacted": True, "old": "111122223333", "new": "444455556666", "note": "x"},
            _MASKED_WHOLE,
        ),
    ],
)
def test_a_client_blob_under_an_identity_key_cannot_smuggle_digits(blob, expected) -> None:
    """Every shape above is something a request body can put in ``extraMetadata``. None of the digits
    in any of them may come back, whichever branch the shape lands on."""
    from app.services.records import public_encode

    served = public_encode({"id": "artisan-1", "extraMetadata": {"aadhaarNumber": blob}})

    surface = repr(served["extraMetadata"]["aadhaarNumber"])
    for digits in ("123456789012", "987654321098", "111122223333", "444455556666"):
        assert digits not in surface, f"{digits} crossed inside {surface}"
    assert served["extraMetadata"]["aadhaarNumber"] == expected


def test_a_forged_placeholder_is_re_derived_rather_than_believed() -> None:
    """The audit SHAPE with a wording that is not one of ours. It is re-derived from the emptiness of
    each side, so the values are gone whatever a caller wrote — and the entry still renders."""
    from app.services.records import public_encode

    served = public_encode(
        {"id": "r1", "extraMetadata": {"pehchanCardNumber": {"old": "PMVY1", "new": "PMVY2", "redacted": True}}}
    )

    assert served["extraMetadata"]["pehchanCardNumber"] == {
        "old": "(value recorded)",
        "new": "(value replaced)",
        "redacted": True,
    }


def test_every_redacted_change_wording_is_in_the_closed_pair_set() -> None:
    """THE DRIFT GUARD BETWEEN THE WRITER AND THE READER. ``records._mask_identity_node`` decides
    "this entry is already one of ours, leave it alone" by comparing ``(old, new)`` against
    ``access.REDACTED_PLACEHOLDER_PAIRS`` rather than by trusting the ``redacted`` key. A fifth
    transition, or a reworded one, that nobody added to that set would be RE-DERIVED on the way out —
    silently rewriting a "(cleared)" into a "(value replaced)". Drive all four branches and check."""
    from app.services.access import REDACTED_PLACEHOLDER_PAIRS, _redacted_change

    produced = {
        (entry["old"], entry["new"])
        for entry in (
            _redacted_change(None, None),
            _redacted_change("9876543210", None),
            _redacted_change("9876543210", "9000000000"),
            _redacted_change(None, "9876543210"),
        )
    }

    assert produced == set(REDACTED_PLACEHOLDER_PAIRS)


def test_a_genuine_placeholder_is_returned_byte_for_byte(ledger) -> None:
    """The recognised entry must come out IDENTICAL, not re-derived: re-deriving
    "(value recorded)"/"(cleared)" would see two non-empty strings and turn a retraction into a
    replacement, which is a false statement about what an editor did."""
    changes = _revise(ledger, {"aadhaarNumber": None})
    written = dict(changes["aadhaarNumber"])

    assert _served(changes)["aadhaarNumber"] == written


# ------------------------------------------------------- the OTHER blob keyed by column name
#
# ``extraMetadata.fieldProvenance`` is ``{column: {by, byName, at}}``, written by
# ``records.merge_field_provenance`` for every field an edit CHANGED — and ``aadhaarNumber`` is not
# in ``PROVENANCE_SKIP_FIELDS``, so editing an identity column leaves a stamp under exactly the key
# name the identity walk matches on. Masking that stamp was a live defect, not caution: the
# provenance panel (``frontend/components/FieldProvenance.tsx``, rows from
# ``Object.entries(provenance)``) was handed ``byName: "XXXX XXXX XXXX"`` and named a mask as the
# person who filled the field in. The stamp is the one of the three shapes that is NOT
# client-writable — ``merge_field_provenance`` drops ``fieldProvenance`` from the incoming body AND
# from the stored seed — so it is passed through.


def test_a_provenance_stamp_under_an_identity_key_still_names_its_editor() -> None:
    from app.services.records import public_encode

    stamp = {"by": "usr_1", "byName": "Asha Sharma", "at": "2026-08-22T10:00:00+00:00"}
    served = public_encode(
        {
            "id": "artisan-1",
            "aadhaarNumber": "123456789012",
            "extraMetadata": {"fieldProvenance": {"aadhaarNumber": stamp, "name": stamp}},
        }
    )

    assert served["extraMetadata"]["fieldProvenance"]["aadhaarNumber"] == stamp
    # And the column itself is still masked, which is the whole point of the walk.
    assert served["aadhaarNumber"] == "XXXX XXXX 9012"


def test_a_stamp_carrying_an_unknown_key_fails_towards_the_mask() -> None:
    """The stamp's key set is retyped in ``records._PROVENANCE_STAMP_KEYS`` (the measurement half is
    built key by key inside ``MeasurementProvenance.stamp`` and has no constant to import), so the
    direction of a mismatch matters: an unrecognised key must lose the who/when line rather than
    turn the walk into a pass-through a caller can aim at."""
    from app.services.records import public_encode

    served = public_encode(
        {
            "id": "artisan-1",
            "extraMetadata": {
                "fieldProvenance": {
                    "aadhaarNumber": {"by": "usr_1", "at": "2026-08-22", "smuggled": "123456789012"}
                }
            },
        }
    )

    assert served["extraMetadata"]["fieldProvenance"]["aadhaarNumber"] == "XXXX XXXX XXXX"



def test_a_plain_artisan_row_is_masked_exactly_as_before() -> None:
    """THE PATH THAT MUST NOT HAVE MOVED. A scalar under an identity key is the artisan row itself,
    which is every read in the portal; the container branch is the rare case and must not have cost
    the common one its last four digits."""
    from app.services.records import public_encode

    encoded = public_encode({"id": "artisan-1", "aadhaarNumber": "123456789012"})

    assert encoded["aadhaarNumber"] == "XXXX XXXX 9012"

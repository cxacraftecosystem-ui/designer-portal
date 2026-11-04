import asyncio
from types import SimpleNamespace

import pytest

from app.services import access
from app.services.access import REVISION_REDACTED_FIELDS, REVISION_SKIP_FIELDS, record_revision

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

_SECRETS = (
    "9876543210",
    "kamla@example.test",
    "House 12, Potters' Lane, Bagru",
    "123456789012",
    "PMVY12345678",
)


class _Recorder:
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
    artisan = SimpleNamespace(**{**_POPULATED, **(record or {})})
    editor = SimpleNamespace(id="editor-7", name="Second Researcher")
    asyncio.run(record_revision(artisan, editor, payload, "artisan"))
    if not ledger.created:
        return None
    return ledger.created[-1]["changes"].data


def _blob(changes: dict) -> str:
    return repr(changes)



def test_clearing_a_phone_number_does_not_copy_it_into_the_ledger(ledger) -> None:
    changes = _revise(ledger, {"phone": None})

    assert changes is not None, "the edit must still be audited"
    assert "9876543210" not in _blob(changes)


def test_no_redacted_column_leaks_its_value_on_retraction(ledger) -> None:
    for field in sorted(REVISION_REDACTED_FIELDS):
        ledger.created.clear()
        changes = _revise(ledger, {field: None})

        assert changes is not None, f"clearing {field} must still be audited"
        assert field in changes, f"clearing {field} must name the field"
        text = _blob(changes)
        for secret in _SECRETS:
            assert secret not in text, f"clearing {field} leaked {secret!r}"


def test_replacing_one_number_with_another_leaks_neither(ledger) -> None:
    changes = _revise(ledger, {"phone": "9000000001"})

    assert "9876543210" not in _blob(changes)
    assert "9000000001" not in _blob(changes)


def test_filling_an_empty_column_does_not_store_the_new_value(ledger) -> None:
    changes = _revise(ledger, {"phone": "9000000001"}, record={"phone": None})

    assert "9000000001" not in _blob(changes)



def test_a_retraction_still_records_which_field_who_and_when(ledger) -> None:
    _revise(ledger, {"phone": None})

    row = ledger.created[-1]
    assert row["editedById"] == "editor-7"
    assert row["recordType"] == "artisan"
    assert row["recordId"] == "artisan-1"
    assert "phone" in row["changes"].data


def test_a_silent_contact_edit_is_impossible(ledger) -> None:
    changes = _revise(ledger, {"phone": "9000000001"})

    assert changes is not None
    assert "phone" in changes
    assert ledger.created[-1]["editedById"] == "editor-7"


def test_the_direction_of_the_change_is_legible(ledger) -> None:
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
    changes = _revise(ledger, {"phone": ""}, record={"phone": None})

    assert changes is not None, "the churn is logged the same way every other column's is"
    assert changes["phone"]["old"] == "(empty)"
    assert changes["phone"]["new"] == "(still empty)"
    assert changes["phone"]["old"] != changes["phone"]["new"]
    assert changes["phone"]["redacted"] is True


def test_the_entry_is_marked_redacted_so_a_reader_can_tell(ledger) -> None:
    changes = _revise(ledger, {"phone": None})

    assert changes["phone"]["redacted"] is True



def test_an_ordinary_field_still_records_its_real_values(ledger) -> None:
    changes = _revise(ledger, {"notes": "Nothing to report."})

    assert changes["notes"]["old"] == "Met at the Bagru fair."
    assert changes["notes"]["new"] == "Nothing to report."
    assert "redacted" not in changes["notes"]


def test_one_payload_redacts_only_the_redacted_half(ledger) -> None:
    changes = _revise(ledger, {"phone": None, "notes": "Nothing to report."})

    assert changes["notes"]["new"] == "Nothing to report."
    assert "9876543210" not in _blob(changes)


def test_nothing_meaningful_changed_still_writes_no_row(ledger) -> None:
    assert _revise(ledger, {"phone": "9876543210"}) is None


def test_the_two_sets_do_not_overlap() -> None:
    assert not (REVISION_REDACTED_FIELDS & REVISION_SKIP_FIELDS)


def test_the_redacted_set_is_the_five_columns_the_comment_argues_for() -> None:
    assert sorted(REVISION_REDACTED_FIELDS) == [
        "aadhaarNumber",
        "address",
        "email",
        "pehchanCardNumber",
        "phone",
    ]



def _served(changes: dict) -> dict:
    from app.services.records import public_encode

    return public_encode([{"id": "revision-1", "changes": changes}])[0]["changes"]


def test_a_redacted_entry_reaches_the_reader_with_its_shape_intact(ledger) -> None:
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
    changes = _revise(ledger, {"phone": None, "email": None, "address": None})

    served = _served(changes)

    for column in ("phone", "email", "address"):
        assert served[column] == {
            "old": "(value recorded)",
            "new": "(cleared)",
            "redacted": True,
        }


def test_an_ordinary_column_in_the_same_blob_still_carries_its_values(ledger) -> None:
    changes = _revise(ledger, {"notes": None, "aadhaarNumber": None})

    served = _served(changes)

    assert served["notes"] == {"old": "Met at the Bagru fair.", "new": None}


def test_a_historical_row_written_before_the_redaction_shows_no_digits() -> None:
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



_MASKED_WHOLE = "XXXX XXXX XXXX"
_RETRACTION = {"old": "(value recorded)", "new": "(cleared)", "redacted": True}


@pytest.mark.parametrize(
    ("blob", "expected"),
    [
        ({"redacted": True, "note": "123456789012"}, _MASKED_WHOLE),
        ({"old": 987654321098}, _RETRACTION),
        ({"old": 987654321098, "extra": [123456789012, 5.5]}, _MASKED_WHOLE),
        (["123456789012"], _MASKED_WHOLE),
        ({"123456789012": "hidden in the key"}, _MASKED_WHOLE),
        (
            {"redacted": True, "old": "111122223333", "new": "444455556666", "note": "x"},
            _MASKED_WHOLE,
        ),
    ],
)
def test_a_client_blob_under_an_identity_key_cannot_smuggle_digits(blob, expected) -> None:
    from app.services.records import public_encode

    served = public_encode({"id": "artisan-1", "extraMetadata": {"aadhaarNumber": blob}})

    surface = repr(served["extraMetadata"]["aadhaarNumber"])
    for digits in ("123456789012", "987654321098", "111122223333", "444455556666"):
        assert digits not in surface, f"{digits} crossed inside {surface}"
    assert served["extraMetadata"]["aadhaarNumber"] == expected


def test_a_forged_placeholder_is_re_derived_rather_than_believed() -> None:
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
    changes = _revise(ledger, {"aadhaarNumber": None})
    written = dict(changes["aadhaarNumber"])

    assert _served(changes)["aadhaarNumber"] == written



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
    assert served["aadhaarNumber"] == "XXXX XXXX 9012"


def test_a_stamp_carrying_an_unknown_key_fails_towards_the_mask() -> None:
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
    from app.services.records import public_encode

    encoded = public_encode({"id": "artisan-1", "aadhaarNumber": "123456789012"})

    assert encoded["aadhaarNumber"] == "XXXX XXXX 9012"

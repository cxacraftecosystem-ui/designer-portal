import asyncio
import re
from datetime import UTC, datetime, timedelta
from pathlib import Path
from types import SimpleNamespace

import pytest
from fastapi import HTTPException

from app.services import dictation_consent
from app.services.dictation_cap import (
    DAY_BOUNDARY_PHRASE,
    Allowance,
    allowance_payload,
    cap_refusal,
    configured_cap,
    ist_day,
)
from app.services.dictation_consent import (
    DICTATION,
    MAX_DEVICE_CLOCK_SKEW,
    SENDS,
    ConsentRuleViolation,
    ConsentWritePlan,
    DictationConsent,
    Operation,
    Send,
    consent_keys,
    consent_of,
    decision_payload,
    decision_plans,
    gate_refusal,
    legacy_waiting_sentence,
    send_for,
)

SCHEMA_PRISMA = Path(__file__).resolve().parents[1] / "prisma" / "schema.prisma"
MIGRATION = (
    Path(__file__).resolve().parents[1]
    / "prisma"
    / "migrations"
    / "20260812120000_dw_dictation_consent_and_cap"
    / "migration.sql"
)

AT = datetime(2026, 8, 12, 9, 14, 2, tzinfo=UTC)

_SUMMARY_COLUMNS = (
    "title",
    "templateId",
    "status",
    "workshopCode",
    "workshopKind",
    "scheme",
    "craftName",
    "clusterName",
    "state",
    "district",
    "venue",
    "startDate",
    "endDate",
    "designerName",
    "implementingAgency",
    "sponsor",
    "notes",
    "workshopId",
    "createdById",
    "createdAt",
    "updatedAt",
    "deletedAt",
    "dictationConsentAt",
    "dictationConsentById",
    "reviewNotes",
    "reviewedById",
    "reviewedAt",
    "submissionRound",
)


def _workshop_row(**overrides):
    columns = dict.fromkeys(_SUMMARY_COLUMNS)
    columns["id"] = "wsp_1"
    columns["dictationConsent"] = "NOT_RECORDED"
    columns["submissionRound"] = 0
    columns.update(overrides)
    return SimpleNamespace(**columns)


def assert_sentence(text: str) -> None:
    assert text and text.strip(), "a refusal must say something"
    assert len(text) > 30, f"too terse to be an explanation: {text!r}"
    assert text.rstrip().endswith("."), f"not a sentence: {text!r}"
    assert "code " not in text.lower(), f"names an error code: {text!r}"



@pytest.mark.parametrize(
    "stored", ["NOT_RECORDED", "GRANTED", "REFUSED"]
)
def test_the_three_stored_answers_read_back_as_themselves(stored):
    assert consent_of(_workshop_row(dictationConsent=stored)) is DictationConsent(stored)


def test_an_enum_member_reads_back_as_well_as_a_plain_string():
    assert consent_of(_workshop_row(dictationConsent=DictationConsent.GRANTED)) is (
        DictationConsent.GRANTED
    )


@pytest.mark.parametrize("stored", [None, "", "granted", "YES", "TRUE", "NOT_A_TOKEN", 1])
def test_anything_unreadable_reads_as_no_answer_which_is_consent_by_default(stored):
    assert consent_of(_workshop_row(dictationConsent=stored)) is DictationConsent.NOT_RECORDED
    assert gate_refusal(consent_of(_workshop_row(dictationConsent=stored))) is None


def test_a_row_with_no_consent_column_at_all_reads_as_no_answer():
    assert consent_of(SimpleNamespace(id="wsp_1")) is DictationConsent.NOT_RECORDED



def test_only_a_refusal_stops_the_gate():
    assert gate_refusal(DictationConsent.GRANTED) is None
    assert gate_refusal(DictationConsent.NOT_RECORDED) is None
    assert gate_refusal(DictationConsent.REFUSED) is not None


def test_the_old_waiting_sentence_is_kept_only_to_recognise_stored_notes():
    unasked = legacy_waiting_sentence(DICTATION)
    refused = gate_refusal(DictationConsent.REFUSED)
    assert unasked != refused
    assert "record the artisan's answer" in unasked
    assert "that is the answer on record" in refused
    assert "if the artisan has since agreed" in refused.lower()


def test_the_refusal_never_tells_a_designer_to_try_again():
    sentence = gate_refusal(DictationConsent.REFUSED)
    assert_sentence(sentence)
    assert "try again" not in sentence.lower()
    assert "type the words in" in sentence.lower()



def test_the_two_dictation_sentences_survive_this_refactor_byte_for_byte():
    assert gate_refusal(DictationConsent.REFUSED) == (
        "This workshop's recordings may not be sent to the transcription service — that is the "
        "answer on record — so this dictation cannot be written down there. Type the words in "
        "instead. If the artisan has since agreed, change that answer on the workshop's own "
        "screen; nothing on this field can change it."
    )
    assert legacy_waiting_sentence(DICTATION) == (
        "Nobody has recorded yet whether recordings from this workshop may be sent to the "
        "transcription service, so this dictation cannot be written down there. Type the words in "
        "instead. Open the workshop's own screen and record the artisan's answer to that question — "
        "until somebody does, this stays unavailable."
    )
    assert gate_refusal(DictationConsent.REFUSED, DICTATION) == gate_refusal(
        DictationConsent.REFUSED
    )


@pytest.mark.parametrize("compose", [lambda d: gate_refusal(DictationConsent.REFUSED, d), legacy_waiting_sentence])
def test_no_verb_but_dictation_is_refused_with_a_sentence_about_dictation(compose):
    for verb, described in SENDS.items():
        sentence = compose(described)
        assert "dictation" not in sentence.lower(), f"{verb} is still told about dictation"
        assert "type the words in" not in sentence.lower(), f"{verb} offers a keyboard"


def test_each_verb_names_the_service_its_route_actually_posts_to():
    assert SENDS["PROOFREAD"].destination == "OpenAI's language model"
    assert SENDS["EXPAND"].destination == "OpenAI's language model"
    assert SENDS["TRANSLATE"].destination == "OpenAI's language model"
    assert SENDS["CAPTION"].destination == "Google's Gemini"
    assert SENDS["SUBTITLES"].destination == "the transcription service"
    assert DICTATION.destination == "the transcription service"
    for verb in ("PROOFREAD", "EXPAND", "TRANSLATE", "CAPTION"):
        assert "transcription" not in gate_refusal(DictationConsent.REFUSED, SENDS[verb])


def test_each_verb_names_the_material_it_actually_sends():
    assert "photograph or a video" in SENDS["CAPTION"].consequence
    assert "recording" in SENDS["SUBTITLES"].consequence
    assert "passage" in SENDS["PROOFREAD"].consequence
    assert "note" in SENDS["EXPAND"].consequence
    assert "passage" in SENDS["TRANSLATE"].consequence


def test_a_media_verb_names_every_kind_of_file_its_route_would_accept():
    from app.api.routes.design_workshops import _VERB_MEDIA_TYPES

    for verb, (_tokens, in_words) in _VERB_MEDIA_TYPES.items():
        described = SENDS[verb]
        assert in_words in described.consequence, (
            f"{verb} accepts {in_words} and its consent refusal says "
            f"{described.consequence!r} — one file, two names, one refusal apart"
        )

    assert "recording" not in SENDS["SUBTITLES"].alternative


def test_only_the_dictation_answer_claims_the_artisan_was_asked_about_recordings():
    assert DICTATION.material == "recordings"
    assert {described.material for described in SENDS.values()} == {"material"}


@pytest.mark.parametrize("verb", sorted(SENDS))
def test_both_consent_states_keep_their_distinct_next_moves_for_every_verb(verb):
    assert gate_refusal(DictationConsent.NOT_RECORDED, SENDS[verb]) is None, "no answer sends"
    unasked = legacy_waiting_sentence(SENDS[verb])
    refused = gate_refusal(DictationConsent.REFUSED, SENDS[verb])
    assert unasked != refused
    assert_sentence(unasked)
    assert_sentence(refused)
    assert "record the artisan's answer" in unasked
    assert "that is the answer on record" in refused
    assert "if the artisan has since agreed" in refused.lower()
    for sentence in (unasked, refused):
        assert "try again" not in sentence.lower()


def test_every_verb_this_server_runs_has_a_sentence_written_for_it():
    from app.services.ai_verbs import Verb

    assert {verb.value for verb in Verb} == set(SENDS)
    for verb in Verb:
        assert send_for(verb.value) is SENDS[verb.value]
        assert send_for(verb) is SENDS[verb.value]


def test_an_unknown_verb_is_never_described_as_dictation():
    unknown = send_for("EMBROIDER")
    assert unknown is not DICTATION
    sentence = gate_refusal(DictationConsent.REFUSED, unknown)
    assert_sentence(sentence)
    assert "dictation" not in sentence.lower()


def test_a_send_description_with_a_hole_in_it_is_refused_at_construction():
    with pytest.raises(ConsentRuleViolation):
        Send(material="", destination="x", consequence="y")
    with pytest.raises(ConsentRuleViolation):
        Send(material="material", destination="x", consequence="")
    with pytest.raises(ConsentRuleViolation):
        Send(material="material", destination="x", consequence="y", alternative="Do this instead.")


def test_no_verbs_alternative_offers_something_this_app_cannot_do():
    from app.services.stage_schema import stages

    caption_fields = [
        field
        for stage in stages()
        for entity in stage.entities
        for field in entity.fields
        if getattr(field, "caption_for", "")
    ]
    assert caption_fields, "the caption box CAPTION's refusal points at does not exist"
    assert "caption box" in SENDS["CAPTION"].alternative
    assert "where the stage has one" in SENDS["CAPTION"].alternative

    assert "in your own words" in SENDS["EXPAND"].alternative
    for verb in ("PROOFREAD", "TRANSLATE", "SUBTITLES"):
        assert SENDS[verb].alternative.startswith("Nothing was sent")



def test_one_decision_makes_exactly_two_writes_and_names_one_row():
    plans = decision_plans(
        workshop_id="wsp_1", decision=DictationConsent.GRANTED, actor_id="usr_2", at=AT
    )
    assert list(plans) == [plans.workshop, plans.decision]

    assert plans.workshop.table == "DesignWorkshop"
    assert plans.workshop.operation is Operation.UPDATE
    assert plans.workshop.where == {"id": "wsp_1"}
    assert plans.workshop.data == {
        "dictationConsent": "GRANTED",
        "dictationConsentAt": AT,
        "dictationConsentById": "usr_2",
    }

    assert plans.decision.table == "Consent"
    assert plans.decision.operation is Operation.CREATE
    assert plans.decision.where is None
    assert plans.decision.data == {
        "subject_type": "WORKSHOP",
        "subject_id": "wsp_1",
        "purpose": "EXTERNAL_PROCESSING",
        "state": "GRANTED",
        "basis": "VERBAL_RECORDED",
        "source": "researcher",
        "note": None,
        "actor_id": "usr_2",
        "recorded_at": AT,
    }


def test_the_workshop_write_touches_only_the_three_consent_columns():
    plans = decision_plans(
        workshop_id="wsp_1", decision=DictationConsent.REFUSED, actor_id="usr_2", at=AT
    )
    assert set(plans.workshop.data) == {
        "dictationConsent",
        "dictationConsentAt",
        "dictationConsentById",
    }


def test_the_courtyard_moment_is_what_lands_on_the_workshop():
    in_a_courtyard = datetime(2026, 7, 29, 11, 5, tzinfo=UTC)
    plans = decision_plans(
        workshop_id="wsp_1",
        decision=DictationConsent.GRANTED,
        actor_id="usr_2",
        at=AT,
        recorded_at=in_a_courtyard,
    )
    assert plans.workshop.data["dictationConsentAt"] == in_a_courtyard
    assert plans.decision.data["recorded_at"] == in_a_courtyard
    assert "createdAt" not in plans.decision.data


def test_a_consent_recorded_against_the_server_is_dated_to_that_moment():
    plans = decision_plans(
        workshop_id="wsp_1", decision=DictationConsent.GRANTED, actor_id="usr_2", at=AT
    )
    assert plans.decision.data["recorded_at"] == AT
    assert plans.workshop.data["dictationConsentAt"] == AT


def test_a_note_is_trimmed_to_nothing_rather_than_stored_blank():
    assert (
        decision_plans(
            workshop_id="wsp_1",
            decision=DictationConsent.REFUSED,
            actor_id="usr_2",
            at=AT,
            note="   ",
        ).decision.data["note"]
        is None
    )
    assert (
        decision_plans(
            workshop_id="wsp_1",
            decision=DictationConsent.REFUSED,
            actor_id="usr_2",
            at=AT,
            note="  she asked that the audio stay on the phone  ",
        ).decision.data["note"]
        == "she asked that the audio stay on the phone"
    )


def test_not_recorded_cannot_be_recorded_as_a_decision():
    with pytest.raises(ConsentRuleViolation) as exc:
        decision_plans(
            workshop_id="wsp_1",
            decision=DictationConsent.NOT_RECORDED,
            actor_id="usr_2",
            at=AT,
        )
    assert_sentence(str(exc.value))
    assert "REFUSED" in str(exc.value)


def test_an_unsigned_consent_is_refused():
    with pytest.raises(ConsentRuleViolation) as exc:
        decision_plans(
            workshop_id="wsp_1", decision=DictationConsent.GRANTED, actor_id="  ", at=AT
        )
    assert_sentence(str(exc.value))


def test_a_consent_that_names_no_workshop_is_refused():
    with pytest.raises(ConsentRuleViolation) as exc:
        decision_plans(
            workshop_id="", decision=DictationConsent.GRANTED, actor_id="usr_2", at=AT
        )
    assert_sentence(str(exc.value))


def test_a_device_clock_in_the_future_is_refused_and_never_corrected():
    with pytest.raises(ConsentRuleViolation) as exc:
        decision_plans(
            workshop_id="wsp_1",
            decision=DictationConsent.GRANTED,
            actor_id="usr_2",
            at=AT,
            recorded_at=AT + timedelta(days=200),
        )
    assert_sentence(str(exc.value))
    assert "future" in str(exc.value)


def test_ordinary_handset_clock_drift_is_accepted():
    drifted = AT + MAX_DEVICE_CLOCK_SKEW - timedelta(seconds=1)
    plans = decision_plans(
        workshop_id="wsp_1",
        decision=DictationConsent.GRANTED,
        actor_id="usr_2",
        at=AT,
        recorded_at=drifted,
    )
    assert plans.workshop.data["dictationConsentAt"] == drifted


def test_a_consent_recorded_before_the_server_heard_of_it_is_always_accepted():
    long_ago = AT - timedelta(days=90)
    assert (
        decision_plans(
            workshop_id="wsp_1",
            decision=DictationConsent.GRANTED,
            actor_id="usr_2",
            at=AT,
            recorded_at=long_ago,
        ).workshop.data["dictationConsentAt"]
        == long_ago
    )


def test_a_moment_with_no_offset_is_read_as_utc_rather_than_crashing():
    naive_past = datetime(2026, 8, 12, 6, 0, 0)  # noqa: DTZ001 - the missing offset is the subject
    plans = decision_plans(
        workshop_id="wsp_1",
        decision=DictationConsent.GRANTED,
        actor_id="usr_2",
        at=AT,
        recorded_at=naive_past,
    )
    assert plans.workshop.data["dictationConsentAt"] == naive_past.replace(tzinfo=UTC)

    naive_future = datetime(2027, 1, 1, 0, 0, 0)  # noqa: DTZ001 - as above
    with pytest.raises(ConsentRuleViolation) as exc:
        decision_plans(
            workshop_id="wsp_1",
            decision=DictationConsent.GRANTED,
            actor_id="usr_2",
            at=AT,
            recorded_at=naive_future,
        )
    assert "future" in str(exc.value), "a naive future moment must reach the clock refusal, not a 500"


def test_a_naive_server_clock_does_not_crash_the_comparison_either():
    naive_now = datetime(2026, 8, 12, 9, 14, 2)  # noqa: DTZ001 - the missing offset is the subject
    plans = decision_plans(
        workshop_id="wsp_1", decision=DictationConsent.REFUSED, actor_id="usr_2", at=naive_now
    )
    assert plans.workshop.data["dictationConsentAt"] == naive_now.replace(tzinfo=UTC)



def test_a_consent_can_never_be_written_into_a_stage_entry():
    from app.services import dictation_consent as module

    with pytest.raises(ConsentRuleViolation) as exc:
        ConsentWritePlan(
            table=module.STAGE_TABLE,
            operation=Operation.UPDATE,
            where={"id": "ent_1"},
            data={"data": {"dictationConsent": "GRANTED"}},
        )
    assert module.STAGE_TABLE in str(exc.value)
    assert_sentence(str(exc.value))


def test_the_executor_has_no_writer_for_the_stage_table_either():
    from app.services import dictation_consent as module

    with pytest.raises(ConsentRuleViolation):
        module._writable_model(module.STAGE_TABLE)
    assert module.STAGE_TABLE not in module.WRITABLE_TABLES


def test_the_writable_set_is_the_two_tables_and_nothing_else():
    from app.services import dictation_consent as module

    assert sorted(module.WRITABLE_TABLES) == ["Consent", "DesignWorkshop"]


def test_an_update_must_name_one_row_and_a_create_must_name_none():
    with pytest.raises(ConsentRuleViolation):
        ConsentWritePlan(table="DesignWorkshop", operation=Operation.UPDATE, data={})
    with pytest.raises(ConsentRuleViolation):
        ConsentWritePlan(
            table="Consent",
            operation=Operation.CREATE,
            data={},
            where={"id": "x"},
        )



def test_the_three_consent_keys_reach_the_clients():
    assert consent_keys(
        _workshop_row(
            dictationConsent="GRANTED", dictationConsentAt=AT, dictationConsentById="usr_2"
        )
    ) == {
        "dictationConsent": "GRANTED",
        "dictationConsentAt": AT.isoformat(),
        "dictationConsentById": "usr_2",
    }


def test_the_consent_key_is_never_null_on_the_wire():
    assert consent_keys(_workshop_row(dictationConsent=None))["dictationConsent"] == (
        "NOT_RECORDED"
    )
    assert consent_keys(_workshop_row(dictationConsent="WHAT"))["dictationConsent"] == (
        "NOT_RECORDED"
    )


def test_the_display_name_is_not_in_the_summary_keys():
    assert "dictationConsentByName" not in consent_keys(_workshop_row())


def test_the_workshop_header_carries_the_consent_and_not_the_name():
    from app.services.design_workshops import workshop_summary

    summary = workshop_summary(
        _workshop_row(dictationConsent="GRANTED", dictationConsentById="usr_2")
    )
    assert summary["dictationConsent"] == "GRANTED"
    assert summary["dictationConsentById"] == "usr_2"
    assert summary["dictationConsentAt"] is None
    assert "dictationConsentByName" not in summary


def test_a_recorded_decision_carries_both_moments():
    row = SimpleNamespace(
        id="dec_1",
        designWorkshopId="wsp_1",
        decision="GRANTED",
        note=None,
        actorId="usr_2",
        recordedAt=datetime(2026, 7, 29, 11, 5, tzinfo=UTC),
        createdAt=AT,
    )
    assert decision_payload(row) == {
        "id": "dec_1",
        "designWorkshopId": "wsp_1",
        "decision": "GRANTED",
        "note": None,
        "actorId": "usr_2",
        "recordedAt": "2026-07-29T11:05:00+00:00",
        "createdAt": AT.isoformat(),
    }



def test_the_python_vocabulary_and_the_postgres_enum_agree():
    schema = SCHEMA_PRISMA.read_text(encoding="utf-8")
    match = re.search(
        r"^enum DwDictationConsent \{(.*?)^\}", schema, re.DOTALL | re.MULTILINE
    )
    assert match, "enum DwDictationConsent is not in schema.prisma"
    declared = {
        line.strip()
        for line in match.group(1).splitlines()
        if line.strip() and not line.strip().startswith("//")
    }
    assert declared == {c.value for c in DictationConsent}


def test_the_stored_default_is_not_recorded_and_never_granted():
    assert (
        "dictationConsent DwDictationConsent @default(NOT_RECORDED)"
        in SCHEMA_PRISMA.read_text(encoding="utf-8")
    )
    sql = MIGRATION.read_text(encoding="utf-8")
    assert "\"DwDictationConsent\" NOT NULL DEFAULT 'NOT_RECORDED'" in sql
    assert "DEFAULT 'GRANTED'" not in sql


def test_the_decision_log_actor_outlives_the_account():
    schema = SCHEMA_PRISMA.read_text(encoding="utf-8")
    assert (
        'actor User @relation("DwWorkshopConsentDecisionActor", fields: [actorId], '
        "references: [id], onDelete: Restrict)" in schema
    )
    assert 'fields: [dictationConsentById], references: [id], onDelete: SetNull' in schema

    sql = MIGRATION.read_text(encoding="utf-8")
    assert (
        'ALTER TABLE "DwWorkshopConsentDecision" ADD CONSTRAINT '
        '"DwWorkshopConsentDecision_actorId_fkey" FOREIGN KEY ("actorId") REFERENCES "User"("id") '
        "ON DELETE RESTRICT" in sql
    )


def test_the_migration_is_additive_and_says_how_to_roll_back():
    sql = MIGRATION.read_text(encoding="utf-8")
    assert "DROP TABLE \"DwDictationDailyUsage\";" in sql
    assert "DROP TABLE \"DwWorkshopConsentDecision\";" in sql
    assert 'DROP TYPE "DwDictationConsent";' in sql
    body = sql.split("-- CreateEnum", 1)[1]
    assert "DROP COLUMN" not in body
    assert "DROP TABLE" not in body



def test_the_day_turns_over_at_midnight_india_time():
    assert ist_day(datetime(2026, 8, 11, 18, 29, 59, tzinfo=UTC)) == "2026-08-11"
    assert ist_day(datetime(2026, 8, 11, 18, 30, 0, tzinfo=UTC)) == "2026-08-12"


def test_an_evening_in_india_is_still_today():
    assert ist_day(datetime(2026, 8, 12, 15, 30, tzinfo=UTC)) == "2026-08-12"


def test_a_naive_clock_is_read_as_utc_rather_than_shifted_blindly():
    assert ist_day(datetime(2026, 8, 11, 18, 30, 0)) == "2026-08-12"  # noqa: DTZ001


def test_the_day_is_a_plain_calendar_date_string():
    assert re.fullmatch(r"\d{4}-\d{2}-\d{2}", ist_day(AT))



def test_an_uncapped_server_never_refuses_and_counts_down_from_nothing():
    allowance = Allowance(day="2026-08-12", limit=None, used=37)
    assert allowance.uncapped is True
    assert allowance.spent is False
    assert allowance.remaining is None
    assert cap_refusal(allowance) is None


def test_a_cap_refuses_only_once_it_is_reached():
    assert cap_refusal(Allowance(day="2026-08-12", limit=40, used=39)) is None
    assert cap_refusal(Allowance(day="2026-08-12", limit=40, used=40)) is not None


def test_a_cap_lowered_mid_day_still_refuses():
    over = Allowance(day="2026-08-12", limit=10, used=12)
    assert over.spent is True
    assert over.remaining == 0
    assert cap_refusal(over) is not None


def test_a_cap_of_zero_gets_its_own_sentence_and_never_blames_the_designer():
    refusal = cap_refusal(Allowance(day="2026-08-12", limit=0, used=0))
    assert_sentence(refusal)
    assert "all 0" not in refusal
    assert "used up" not in refusal
    assert "daily allowance is set to none" in refusal


def test_the_refusal_names_the_limit_and_when_the_allowance_returns():
    refusal = cap_refusal(Allowance(day="2026-08-12", limit=40, used=40))
    assert_sentence(refusal)
    assert "40" in refusal
    assert DAY_BOUNDARY_PHRASE in refusal
    assert "try again" not in refusal.lower()
    assert "type the words in" in refusal.lower()


def test_the_allowance_a_phone_caches_carries_the_day_it_belongs_to():
    assert allowance_payload(Allowance(day="2026-08-12", limit=40, used=28)) == {
        "dictationsLimit": 40,
        "dictationsUsed": 28,
        "dictationsRemaining": 12,
        "dictationDay": "2026-08-12",
    }


def test_an_uncapped_allowance_reports_nulls_and_not_zeroes():
    payload = allowance_payload(Allowance(day="2026-08-12", limit=None, used=5))
    assert payload["dictationsLimit"] is None
    assert payload["dictationsRemaining"] is None
    assert payload["dictationsUsed"] == 5



def test_a_deployment_with_no_settings_row_is_uncapped():
    assert configured_cap(None) is None


def test_null_is_uncapped_and_zero_is_a_real_setting():
    assert configured_cap(SimpleNamespace(dwDictationDailyCap=None)) is None
    assert configured_cap(SimpleNamespace(dwDictationDailyCap=0)) == 0
    assert configured_cap(SimpleNamespace(dwDictationDailyCap=40)) == 40


def test_a_settings_row_that_predates_the_column_is_uncapped():
    assert configured_cap(SimpleNamespace()) is None


@pytest.mark.parametrize("stored", [-1, -40, "forty", object()])
def test_a_cap_nobody_could_have_meant_leaves_the_server_uncapped(stored):
    assert configured_cap(SimpleNamespace(dwDictationDailyCap=stored)) is None



def test_zero_may_be_typed_and_a_negative_may_not():
    from app.schemas.settings import MAX_DICTATION_DAILY_CAP, AppSettingUpdate

    assert AppSettingUpdate(dwDictationDailyCap=0).dwDictationDailyCap == 0
    assert (
        AppSettingUpdate(dwDictationDailyCap=MAX_DICTATION_DAILY_CAP).dwDictationDailyCap
        == MAX_DICTATION_DAILY_CAP
    )
    for refused in (-1, MAX_DICTATION_DAILY_CAP + 1):
        with pytest.raises(ValueError):
            AppSettingUpdate(dwDictationDailyCap=refused)


def test_an_explicit_null_is_distinguishable_from_never_mentioning_the_cap():
    from app.schemas.settings import AppSettingUpdate

    lifting = AppSettingUpdate(dwDictationDailyCap=None)
    silent = AppSettingUpdate(batchWindowEnabled=True)

    assert "dwDictationDailyCap" in lifting.model_fields_set
    assert "dwDictationDailyCap" not in silent.model_fields_set
    assert "dwDictationDailyCap" not in lifting.model_dump(exclude_none=True)


def test_the_settings_route_reads_model_fields_set_for_the_cap_and_not_the_dump():
    source = (
        Path(__file__).resolve().parents[1] / "app" / "api" / "routes" / "settings.py"
    ).read_text(encoding="utf-8")
    assert '"dwDictationDailyCap" in payload.model_fields_set' in source



class _Clip:
    def __init__(self, size: int = 4096):
        self._bytes = b"\x00" * size
        self.filename = "dictation.webm"
        self.content_type = "audio/webm"
        self.reads = 0
        self._spent = False

    async def read(self, size: int = -1) -> bytes:
        if self._spent:
            return b""
        self._spent = True
        self.reads += 1
        if size is None or size < 0 or size >= len(self._bytes):
            return self._bytes
        return self._bytes


def _plain_request():
    return SimpleNamespace(headers={})


def _person(role: str):
    return SimpleNamespace(id="usr_1", email="x@example.test", name="Test", role=role)


@pytest.fixture
def dictation(monkeypatch):
    from app.api.routes import design_workshops as routes

    async def _unpaused() -> bool:
        return False

    monkeypatch.setattr(routes.app_settings, "load_transcription_paused", _unpaused)

    seen = SimpleNamespace(provider_calls=[], spends=[], allowance=None, transcribed_for=[])

    async def _workshop(workshop_id, user, **kwargs):
        return _workshop_row(id=workshop_id, dictationConsent=state.consent)

    async def _transcribe(content, filename, mime, settings, *, user_id=None):
        seen.provider_calls.append((len(content), filename, mime))
        seen.transcribed_for.append(user_id)
        return dict(state.result)

    async def _load_allowance(user_id, *, now=None):
        return state.allowance

    async def _spend(user_id, day):
        seen.spends.append((user_id, day))
        return state.allowance.used + 1

    state = SimpleNamespace(
        consent="GRANTED",
        allowance=Allowance(day="2026-08-12", limit=None, used=0),
        result={"status": "COMPLETED", "text": "dabu resist printing", "provider": "deepgram"},
    )

    monkeypatch.setattr(routes, "load_workshop_or_404", _workshop)

    async def _no_ledger_rows(*_a, **_k):
        return {}

    monkeypatch.setattr(routes.dictation_consent.ledger, "current_states", _no_ledger_rows)

    async def _no_linked_artisans(_workshop_ids):
        return []

    monkeypatch.setattr(routes.dictation_consent, "workshop_artisan_ids", _no_linked_artisans)
    monkeypatch.setattr(routes, "transcribe_audio_bytes", _transcribe)
    monkeypatch.setattr(routes, "get_settings", SimpleNamespace)
    monkeypatch.setattr(routes.dictation_cap, "load_allowance", _load_allowance)
    monkeypatch.setattr(routes.dictation_cap, "spend", _spend)

    async def _keep(content, file, result, user, record):
        seen.kept.append((len(content), result.get("status"), getattr(record, "id", None)))
        return "med_1" if result.get("status") == "COMPLETED" else None

    seen.kept = []
    monkeypatch.setattr(routes, "_keep_dictation", _keep)
    return SimpleNamespace(routes=routes, seen=seen, state=state)


@pytest.mark.parametrize("consent", ["NOT_RECORDED", None, "granted"])
def test_a_workshop_with_no_answer_dictates_because_consent_is_assumed_by_default(dictation, consent):
    dictation.state.consent = consent
    payload = asyncio.run(
        dictation.routes.dictate_for_workshop(
            "wsp_1", request=_plain_request(), file=_Clip(), languageHint=None, current_user=_person("DESIGNER")
        )
    )
    assert payload["text"] == "dabu resist printing"
    assert len(dictation.seen.provider_calls) == 1


@pytest.mark.parametrize("consent", ["REFUSED"])
def test_the_workshop_scoped_route_refuses_a_revoked_send_before_touching_a_provider(
    dictation, consent
):
    dictation.state.consent = consent
    with pytest.raises(HTTPException) as exc:
        asyncio.run(
            dictation.routes.dictate_for_workshop(
                "wsp_1", request=_plain_request(), file=_Clip(), languageHint=None, current_user=_person("DESIGNER")
            )
        )
    assert exc.value.status_code == 409
    assert_sentence(str(exc.value.detail))
    assert dictation.seen.provider_calls == []
    assert dictation.seen.spends == []


def test_a_consented_workshop_reaches_the_provider_and_gets_its_words_back(dictation):
    payload = asyncio.run(
        dictation.routes.dictate_for_workshop(
            "wsp_1", request=_plain_request(), file=_Clip(), languageHint="or", current_user=_person("DESIGNER")
        )
    )
    assert payload["text"] == "dabu resist printing"
    assert payload["languageHint"] == "or"
    assert len(dictation.seen.provider_calls) == 1
    assert dictation.seen.spends == [("usr_1", "2026-08-12")]
    assert dictation.seen.transcribed_for == ["usr_1"]


def test_the_consent_gate_is_checked_before_the_cap(dictation):
    dictation.state.consent = "REFUSED"
    dictation.state.allowance = Allowance(day="2026-08-12", limit=10, used=10)
    with pytest.raises(HTTPException) as exc:
        asyncio.run(
            dictation.routes.dictate_for_workshop(
                "wsp_1", request=_plain_request(), file=_Clip(), languageHint=None, current_user=_person("DESIGNER")
            )
        )
    assert exc.value.status_code == 409, "the cap answered a question consent had already settled"


def test_a_spent_allowance_refuses_with_a_429_and_uploads_nothing_to_a_provider(dictation):
    dictation.state.allowance = Allowance(day="2026-08-12", limit=40, used=40)
    with pytest.raises(HTTPException) as exc:
        asyncio.run(
            dictation.routes.dictate_for_workshop(
                "wsp_1", request=_plain_request(), file=_Clip(), languageHint=None, current_user=_person("DESIGNER")
            )
        )
    assert exc.value.status_code == 429
    assert "40" in str(exc.value.detail)
    assert dictation.seen.provider_calls == []
    assert dictation.seen.spends == []


def test_the_refusal_detail_is_a_sentence_and_not_a_dictionary(dictation):
    dictation.state.allowance = Allowance(day="2026-08-12", limit=40, used=41)
    with pytest.raises(HTTPException) as exc:
        asyncio.run(
            dictation.routes.dictate_for_workshop(
                "wsp_1", request=_plain_request(), file=_Clip(), languageHint=None, current_user=_person("DESIGNER")
            )
        )
    assert isinstance(exc.value.detail, str)


def test_a_clip_refused_for_size_does_not_consume_an_allowance(dictation):
    dictation.state.allowance = Allowance(day="2026-08-12", limit=40, used=0)
    with pytest.raises(HTTPException) as exc:
        asyncio.run(
            dictation.routes.dictate_for_workshop(
                "wsp_1",
                request=_plain_request(),
                file=_Clip(size=dictation.routes.DICTATION_MAX_BYTES + 1),
                languageHint=None,
                current_user=_person("DESIGNER"),
            )
        )
    assert exc.value.status_code == 413
    assert dictation.seen.provider_calls == []
    assert dictation.seen.spends == []


def test_an_empty_upload_does_not_consume_an_allowance(dictation):
    with pytest.raises(HTTPException) as exc:
        asyncio.run(
            dictation.routes.dictate_for_workshop(
                "wsp_1", request=_plain_request(), file=_Clip(size=0), languageHint=None, current_user=_person("DESIGNER")
            )
        )
    assert exc.value.status_code == 422
    assert dictation.seen.spends == []


def test_a_server_with_no_provider_configured_does_not_consume_an_allowance(dictation):
    dictation.state.result = {"status": "UNAVAILABLE", "message": "Transcription unavailable: …"}
    with pytest.raises(HTTPException) as exc:
        asyncio.run(
            dictation.routes.dictate_for_workshop(
                "wsp_1", request=_plain_request(), file=_Clip(), languageHint=None, current_user=_person("DESIGNER")
            )
        )
    assert exc.value.status_code == 503
    assert len(dictation.seen.provider_calls) == 1, "the chain was consulted"
    assert dictation.seen.spends == [], "…but nothing reached a provider, so nothing was spent"


@pytest.mark.parametrize("provider_status", ["COMPLETED", "EMPTY", "FAILED", "RATE_LIMITED"])
def test_every_upload_that_reached_a_provider_is_counted(dictation, provider_status):
    dictation.state.result = {"status": provider_status, "text": "", "provider": "deepgram"}
    asyncio.run(
        dictation.routes.dictate_for_workshop(
            "wsp_1", request=_plain_request(), file=_Clip(), languageHint=None, current_user=_person("DESIGNER")
        )
    )
    assert dictation.seen.spends == [("usr_1", "2026-08-12")]


def test_the_two_hundred_carries_the_allowance_so_a_phone_need_never_be_refused_to_learn_it(
    dictation,
):
    dictation.state.allowance = Allowance(day="2026-08-12", limit=40, used=27)
    payload = asyncio.run(
        dictation.routes.dictate_for_workshop(
            "wsp_1", request=_plain_request(), file=_Clip(), languageHint=None, current_user=_person("DESIGNER")
        )
    )
    assert payload["dictationsLimit"] == 40
    assert payload["dictationsUsed"] == 28
    assert payload["dictationsRemaining"] == 12
    assert payload["dictationDay"] == "2026-08-12"


def test_a_counter_that_could_not_be_written_reports_the_count_it_can_stand_behind(
    dictation, monkeypatch
):
    async def _failed_spend(user_id, day):
        return None

    dictation.state.allowance = Allowance(day="2026-08-12", limit=40, used=27)
    monkeypatch.setattr(dictation.routes.dictation_cap, "spend", _failed_spend)
    payload = asyncio.run(
        dictation.routes.dictate_for_workshop(
            "wsp_1", request=_plain_request(), file=_Clip(), languageHint=None, current_user=_person("DESIGNER")
        )
    )
    assert payload["dictationsUsed"] == 27
    assert payload["text"] == "dabu resist printing"


def test_the_id_less_route_transcribes_nothing_and_charges_nothing(dictation, monkeypatch):
    async def _must_not_load_a_workshop(*args, **kwargs):
        raise AssertionError("the retired route has no workshop to load, and must not grow one")

    async def _must_not_read_an_allowance(*args, **kwargs):
        raise AssertionError("a route that transcribes nothing must not touch a designer's allowance")

    monkeypatch.setattr(dictation.routes, "load_workshop_or_404", _must_not_load_a_workshop)
    monkeypatch.setattr(
        dictation.routes.dictation_cap, "load_allowance", _must_not_read_an_allowance
    )

    clip = _Clip()
    with pytest.raises(HTTPException) as exc:
        asyncio.run(
            dictation.routes.dictate(
                file=clip, languageHint=None, current_user=_person("DESIGNER")
            )
        )
    assert exc.value.status_code == 410
    assert dictation.seen.provider_calls == []
    assert dictation.seen.spends == []
    assert clip.reads == 0, "a refusal that reads the upload has spent something to say no"


def test_the_retirement_names_the_url_that_replaced_it(dictation):
    with pytest.raises(HTTPException) as exc:
        asyncio.run(
            dictation.routes.dictate(
                file=_Clip(), languageHint=None, current_user=_person("DESIGNER")
            )
        )
    detail = str(exc.value.detail)
    assert_sentence(detail)
    assert "/design-workshops/{workshop_id}/dictate" not in detail
    assert "update the app to use dictation" in detail.lower()
    assert "try again" not in detail.lower()
    assert "type the answer instead" in detail.lower()


def test_the_probe_answers_without_a_workshop_and_names_where_a_clip_goes(dictation):
    payload = asyncio.run(dictation.routes.dictation_probe(_person("DESIGNER")))
    assert payload["dictatePath"] == "/design-workshops/{workshop_id}/dictate"
    assert payload["allowancePath"] == "/design-workshops/dictation-allowance"
    assert payload["maxBytes"] == dictation.routes.DICTATION_MAX_BYTES
    assert payload["consentRequired"] is True
    assert dictation.seen.provider_calls == []
    assert dictation.seen.spends == []



class _DatabaseTouched(Exception):
    pass


class _Tripwire:
    def __getattr__(self, name: str):
        raise _DatabaseTouched(name)


_CALLER: dict[str, object] = {"user": None}


@pytest.fixture
def api(monkeypatch):
    import sys

    import httpx
    from fastapi import FastAPI

    import app.core.db as core_db
    from app.api.routes import design_workshops as routes
    from app.core import deps

    tripwire = _Tripwire()
    real_db = core_db.db
    monkeypatch.setattr(core_db, "db", tripwire)
    for module in list(sys.modules.values()):
        if (
            getattr(module, "__name__", "").startswith("app.")
            and getattr(module, "db", None) is real_db
        ):
            monkeypatch.setattr(module, "db", tripwire)

    app = FastAPI()
    app.include_router(routes.router, prefix="/api")
    app.dependency_overrides[deps.get_current_user] = lambda: _CALLER["user"]

    def call(role: str, method: str, path: str, body=None, files=None, data=None):
        _CALLER["user"] = _person(role)

        async def run():
            transport = httpx.ASGITransport(app=app)
            async with httpx.AsyncClient(
                transport=transport, base_url="http://dictation.test"
            ) as c:
                response = await c.request(
                    method,
                    f"/api{path}",
                    json=body if files is None and data is None else None,
                    files=files,
                    data=data,
                )
            payload = response.json() if response.content else {}
            detail = payload.get("detail", payload) if isinstance(payload, dict) else payload
            return SimpleNamespace(
                reached=False, status_code=response.status_code, detail=str(detail)
            )

        try:
            return asyncio.run(run())
        except _DatabaseTouched:
            return SimpleNamespace(reached=True, status_code=None, detail="")

    yield call
    _CALLER["user"] = None


CLIP = {"file": ("dictation.webm", b"\x00" * 32, "audio/webm")}

CONSENT_AND_DICTATION = [
    ("POST", "/design-workshops/wsp_1/dictation-consent", {"decision": "GRANTED"}, None),
    ("POST", "/design-workshops/wsp_1/dictate", None, CLIP),
    ("GET", "/design-workshops/dictation-allowance", None, None),
]


@pytest.mark.parametrize(("method", "path", "body", "files"), CONSENT_AND_DICTATION)
@pytest.mark.parametrize("role", ["RESEARCHER", "PROFESSOR"])
def test_only_the_designer_set_may_record_a_consent_or_spend_a_dictation(
    api, role, method, path, body, files
):
    outcome = api(role, method, path, body, files=files)
    assert outcome.reached is False
    assert outcome.status_code == 403


@pytest.mark.parametrize(("method", "path", "body", "files"), CONSENT_AND_DICTATION)
def test_a_designer_reaches_the_handler_and_then_the_database(api, method, path, body, files):
    assert api("DESIGNER", method, path, body, files=files).reached is True


def test_the_consent_route_refuses_a_decision_nobody_can_record(api):
    outcome = api(
        "DESIGNER",
        "POST",
        "/design-workshops/wsp_1/dictation-consent",
        {"decision": "NOT_RECORDED"},
    )
    assert outcome.reached is False
    assert outcome.status_code == 422
    assert "REFUSED" in outcome.detail


@pytest.mark.parametrize("decision", ["MAYBE", "granted", "", "yes"])
def test_the_consent_route_refuses_a_token_postgres_would_have_rejected(api, decision):
    outcome = api(
        "DESIGNER", "POST", "/design-workshops/wsp_1/dictation-consent", {"decision": decision}
    )
    if decision.upper() == "GRANTED":
        assert outcome.reached is True
        return
    assert outcome.reached is False
    assert outcome.status_code == 422


def test_the_consent_route_refuses_an_unparseable_moment(api):
    outcome = api(
        "DESIGNER",
        "POST",
        "/design-workshops/wsp_1/dictation-consent",
        {"decision": "GRANTED", "recordedAt": "last Tuesday"},
    )
    assert outcome.reached is False
    assert outcome.status_code == 422


def test_the_consent_route_is_not_patch(api):
    outcome = api(
        "DESIGNER",
        "PATCH",
        "/design-workshops/wsp_1",
        {"dictationConsent": "GRANTED"},
    )
    assert outcome.reached is False
    assert outcome.status_code == 422



@pytest.mark.parametrize("role", ["DESIGNER", "RESEARCHER", "PROFESSOR"])
def test_the_retired_url_refuses_every_caller_identically(api, role):
    outcome = api(role, "POST", "/design-workshops/dictate", None, files=CLIP)
    assert outcome.reached is False
    assert outcome.status_code == 410
    assert "Update the app to use dictation" in outcome.detail


def test_the_capability_probe_is_answered_by_its_own_route_and_never_by_the_wildcard(api):
    outcome = api("DESIGNER", "GET", "/design-workshops/dictate")
    assert outcome.reached is False, "the wildcard workshop route swallowed the probe again"
    assert outcome.status_code == 200
    assert "/design-workshops/{workshop_id}/dictate" in outcome.detail


def test_the_probe_never_answers_404_for_a_rank(api):
    for role in ("DESIGNER", "RESEARCHER", "PROFESSOR"):
        assert api(role, "GET", "/design-workshops/dictate").status_code != 404



_WHOLE_API: list = []


def _whole_api():
    if not _WHOLE_API:
        from fastapi import FastAPI

        from app.api.router import api_router
        from app.core import deps

        application = FastAPI()
        application.include_router(api_router)
        application.dependency_overrides[deps.get_current_user] = lambda: _CALLER["user"]
        _WHOLE_API.append(application)
    return _WHOLE_API[0]


@pytest.fixture
def mounted(monkeypatch):
    import sys

    import httpx

    import app.core.db as core_db

    application = _whole_api()

    tripwire = _Tripwire()
    real_db = core_db.db
    monkeypatch.setattr(core_db, "db", tripwire)
    for module in list(sys.modules.values()):
        if (
            getattr(module, "__name__", "").startswith("app.")
            and getattr(module, "db", None) is real_db
        ):
            monkeypatch.setattr(module, "db", tripwire)

    def call(role: str, method: str, path: str, files=None):
        _CALLER["user"] = _person(role)

        async def run():
            transport = httpx.ASGITransport(app=application)
            async with httpx.AsyncClient(
                transport=transport, base_url="http://dictation.test"
            ) as c:
                response = await c.request(method, f"/api{path}", files=files)
            payload = response.json() if response.content else {}
            detail = payload.get("detail", payload) if isinstance(payload, dict) else payload
            return SimpleNamespace(
                reached=False, status_code=response.status_code, detail=str(detail)
            )

        try:
            return asyncio.run(run())
        except _DatabaseTouched:
            return SimpleNamespace(reached=True, status_code=None, detail="")

    yield call
    _CALLER["user"] = None


def test_the_probe_is_answered_by_its_own_route_on_the_whole_api(mounted):
    outcome = mounted("DESIGNER", "GET", "/design-workshops/dictate")
    assert outcome.reached is False, "something ahead of the probe swallowed it"
    assert outcome.status_code == 200
    assert "/design-workshops/{workshop_id}/dictate" in outcome.detail


def test_the_retired_url_is_still_retired_on_the_whole_api(mounted):
    outcome = mounted("DESIGNER", "POST", "/design-workshops/dictate", files=CLIP)
    assert outcome.reached is False
    assert outcome.status_code == 410
    assert "Update the app to use dictation" in outcome.detail



def _media(**columns):
    return {"id": "media-1", **columns}


def test_a_design_workshop_upload_names_its_workshop_in_the_columns_it_already_sends():
    row = _media(linkedRecordType="designWorkshop", linkedRecordId="ws-7")
    assert dictation_consent.tagged_workshop_id(row) == "ws-7"


def test_the_tag_is_read_whatever_case_a_client_spelled_it_in():
    assert dictation_consent.tagged_workshop_id(
        _media(linkedRecordType="  DesignWorkshop  ", linkedRecordId=" ws-7 ")
    ) == "ws-7"


def test_a_recording_that_belongs_to_no_design_workshop_is_left_alone():
    for row in (
        _media(linkedRecordType="questionnaireInterview", linkedRecordId="int-1"),
        _media(linkedRecordType=None, linkedRecordId=None),
        _media(),
    ):
        assert dictation_consent.tagged_workshop_id(row) is None
        verdict = dictation_consent.verdict_for(None, workshop_id=None)
        assert verdict.decision is dictation_consent.SendDecision.NOT_WORKSHOP_MATERIAL
        assert verdict.may_send is True
        assert verdict.refusal is None


def test_an_unanswered_workshop_sends_because_consent_is_assumed_by_default():
    verdict = dictation_consent.verdict_for(
        dictation_consent.DictationConsent.NOT_RECORDED, workshop_id="ws-7"
    )
    assert verdict.may_send is True
    assert verdict.workshop_id == "ws-7"
    assert verdict.refusal is None


def test_a_refused_recording_says_the_audio_is_still_kept():
    verdict = dictation_consent.verdict_for(
        dictation_consent.DictationConsent.REFUSED, workshop_id="ws-7"
    )
    assert "still kept with the workshop" in verdict.refusal


def test_a_refusal_on_record_gets_the_other_sentence_and_never_says_ask_again():
    verdict = dictation_consent.verdict_for(
        dictation_consent.DictationConsent.REFUSED, workshop_id="ws-7"
    )
    assert verdict.may_send is False
    assert "that is the answer on record" in verdict.refusal
    assert "Nobody has recorded yet" not in verdict.refusal


def test_a_workshop_whose_consent_could_not_be_read_refuses():
    verdict = dictation_consent.verdict_for(None, workshop_id="ws-gone")
    assert verdict.may_send is False
    assert verdict.refusal == dictation_consent.UNREADABLE


def test_a_granted_workshop_may_send_and_carries_no_sentence():
    verdict = dictation_consent.verdict_for(
        dictation_consent.DictationConsent.GRANTED, workshop_id="ws-7"
    )
    assert verdict.decision is dictation_consent.SendDecision.GRANTED
    assert verdict.may_send is True
    assert verdict.refusal is None


def test_a_verdict_cannot_refuse_without_a_sentence_to_show():
    with pytest.raises(dictation_consent.ConsentRuleViolation):
        dictation_consent.SendVerdict(dictation_consent.SendDecision.REFUSED)
    with pytest.raises(dictation_consent.ConsentRuleViolation):
        dictation_consent.SendVerdict(
            dictation_consent.SendDecision.GRANTED, refusal="something to say"
        )


def test_the_recording_refusal_never_mentions_dictation_or_typing_the_words_in():
    refusal = dictation_consent.verdict_for(
        dictation_consent.DictationConsent.REFUSED, workshop_id="ws-7"
    ).refusal
    assert "dictation" not in refusal.lower()
    assert "Type the words in" not in refusal


def test_a_transcript_on_its_way_to_openai_names_openai_and_not_the_transcription_service():
    refusal = dictation_consent.verdict_for(
        dictation_consent.DictationConsent.REFUSED,
        workshop_id="ws-7",
        send=dictation_consent.REFINEMENT,
    ).refusal
    assert "OpenAI's language model" in refusal
    assert "the transcription service" not in refusal


def test_the_queue_reads_consent_again_before_it_fetches_a_single_byte():
    import asyncio

    from app.services import media_queue

    fetched: list[str] = []
    finalized: list = []

    def _never(*args, **kwargs):
        fetched.append("object storage was reached for a refused recording")
        raise AssertionError(fetched[-1])

    async def _refuse(media, **kwargs):
        return dictation_consent.SendVerdict(
            dictation_consent.SendDecision.REFUSED,
            workshop_id="ws-7",
            refusal="the artisan withdrew",
        )

    async def _record(job, verdict):
        finalized.append(verdict)

    async def _boom(*args, **kwargs):
        raise AssertionError("a provider was contacted for a recording consent had refused")

    job = SimpleNamespace(
        id="job-1",
        jobType="TRANSCRIPTION",
        mediaFileId="m1",
        mediaFile=SimpleNamespace(
            id="m1",
            objectKey="media/u/1/clip.webm",
            originalFilename="clip.webm",
            mimeType="audio/webm",
            transcriptText=None,
            linkedRecordType="designWorkshop",
            linkedRecordId="ws-7",
        ),
    )

    original = (
        media_queue.dictation_consent.transcription_verdict,
        media_queue._finalize_refused_job,
        media_queue.get_object_bytes,
        media_queue.head_object,
        media_queue.download_to_temp,
        media_queue.transcribe_audio_bytes,
    )
    media_queue.dictation_consent.transcription_verdict = _refuse
    media_queue._finalize_refused_job = _record
    media_queue.get_object_bytes = _never
    media_queue.head_object = _never
    media_queue.download_to_temp = _never
    media_queue.transcribe_audio_bytes = _boom
    try:
        asyncio.run(media_queue._process_job(job, SimpleNamespace()))
    finally:
        (
            media_queue.dictation_consent.transcription_verdict,
            media_queue._finalize_refused_job,
            media_queue.get_object_bytes,
            media_queue.head_object,
            media_queue.download_to_temp,
            media_queue.transcribe_audio_bytes,
        ) = original

    assert fetched == [], fetched
    assert len(finalized) == 1, "the job was neither sent nor closed — it would be retried for ever"
    assert finalized[0].refusal == "the artisan withdrew"


def test_a_measurement_job_is_not_touched_by_the_consent_gate():
    import asyncio

    from app.services import media_queue

    reached: list[str] = []

    def _fetch(key, **_kwargs):
        reached.append(key)
        return b"jpegbytes"

    def _unsized(_key):
        return None

    async def _refuse_everything(media, **kwargs):
        raise AssertionError("the consent gate was consulted for a measurement job")

    async def _analysis(*args, **kwargs):
        return {"status": "COMPLETED", "analysis": None}

    async def _applied(job, result):
        reached.append("applied")

    job = SimpleNamespace(
        id="job-2",
        jobType="MEASUREMENT",
        mediaFileId="m2",
        mediaFile=SimpleNamespace(
            id="m2", objectKey="media/u/2/grid.jpg", originalFilename="grid.jpg",
            mimeType="image/jpeg", transcriptText=None,
            linkedRecordType="designWorkshop", linkedRecordId="ws-7",
        ),
    )

    original = (
        media_queue.dictation_consent.transcription_verdict,
        media_queue.get_object_bytes,
        media_queue.head_object,
        media_queue.analyze_measurement_image_bytes,
        media_queue._apply_measurement_result,
    )
    media_queue.dictation_consent.transcription_verdict = _refuse_everything
    media_queue.get_object_bytes = _fetch
    media_queue.head_object = _unsized
    media_queue.analyze_measurement_image_bytes = _analysis
    media_queue._apply_measurement_result = _applied
    try:
        asyncio.run(media_queue._process_job(job, SimpleNamespace()))
    finally:
        (
            media_queue.dictation_consent.transcription_verdict,
            media_queue.get_object_bytes,
            media_queue.head_object,
            media_queue.analyze_measurement_image_bytes,
            media_queue._apply_measurement_result,
        ) = original

    assert reached == ["media/u/2/grid.jpg", "applied"], reached



def test_a_dictation_answers_with_the_kept_clips_media_id(dictation):
    payload = asyncio.run(
        dictation.routes.dictate_for_workshop(
            "wsp_1", request=_plain_request(), file=_Clip(), languageHint=None, current_user=_person("DESIGNER")
        )
    )
    assert payload["text"] == "dabu resist printing"
    assert payload["mediaId"] == "med_1"
    assert dictation.seen.kept == [(4096, "COMPLETED", "wsp_1")], "the clip is kept against its workshop"


def test_a_dictation_nobody_heard_answers_with_no_media_id(dictation):
    dictation.state.result = {"status": "EMPTY", "text": "", "provider": "deepgram"}
    payload = asyncio.run(
        dictation.routes.dictate_for_workshop(
            "wsp_1", request=_plain_request(), file=_Clip(), languageHint=None, current_user=_person("DESIGNER")
        )
    )
    assert payload["mediaId"] is None


@pytest.fixture
def keeping(monkeypatch):
    from app.api.routes import design_workshops as routes
    from app.services import s3, transcript_insights, transcript_pipeline

    seen = SimpleNamespace(puts=[], created=[], stored=[], queued=[], fail_put=False)

    def _put(key, data, *, mime_type):
        if seen.fail_put:
            raise OSError("bucket unreachable")
        seen.puts.append((key, len(data), mime_type))

    async def _create(*, data, **_kw):
        seen.created.append(data)
        return SimpleNamespace(id="med_9", **{k: v for k, v in data.items() if isinstance(k, str)})

    async def _store(media, result, **_kw):
        seen.stored.append((media.id, result.get("status")))
        return SimpleNamespace(version=1)

    async def _enqueue(media_id):
        seen.queued.append(media_id)

    monkeypatch.setattr(s3, "put_object_bytes", _put)
    monkeypatch.setattr(s3, "public_url_for_key", lambda key: None)
    monkeypatch.setattr(routes, "db", SimpleNamespace(mediafile=SimpleNamespace(create=_create)))
    monkeypatch.setattr(routes, "get_settings", lambda: SimpleNamespace(aws_s3_bucket="ci-bucket"))
    monkeypatch.setattr(transcript_pipeline, "store_transcription", _store)
    monkeypatch.setattr(transcript_insights, "enqueue_after_transcript", _enqueue)
    return SimpleNamespace(routes=routes, seen=seen)


_HEARD = {"status": "COMPLETED", "text": "dabu resist printing", "provider": "elevenlabs", "model": "scribe_v2"}


def test_a_heard_clip_is_stored_named_turned_into_a_transcript_and_queued_for_english(keeping):
    record = SimpleNamespace(id="wsp_1", workshopCode="DW-RJ-0012")
    media_id = asyncio.run(keeping.routes._keep_dictation(b"\x00" * 512, _Clip(), dict(_HEARD), _person("DESIGNER"), record))
    assert media_id == "med_9"
    (key, size, mime), = keeping.seen.puts
    assert key.startswith("media/usr_1/") and size == 512 and mime == "audio/webm"
    leaf = key.rsplit("/", 1)[-1]
    assert re.fullmatch(r"DesignWorkshop_Workshop_\d{4}-\d{2}-\d{2}_\d{4}_DW-RJ-0012_Dictation\.webm", leaf), leaf
    (row,) = keeping.seen.created
    assert row["designWorkshop"] == {"connect": {"id": "wsp_1"}}
    assert row["uploadedBy"] == {"connect": {"id": "usr_1"}}
    assert row["linkedRecordType"] == keeping.routes.DICTATION_LINK_TYPE and row["linkedRecordId"] == "wsp_1"
    assert row["mediaType"] == "AUDIO" and row["objectKey"] == key and row["bucket"] == "ci-bucket"
    assert keeping.seen.stored == [("med_9", "COMPLETED")], "the provider's own answer, no second Scribe call"
    assert keeping.seen.queued == ["med_9"]


def test_a_clip_that_cannot_be_stored_still_leaves_the_words(keeping, caplog):
    keeping.seen.fail_put = True
    record = SimpleNamespace(id="wsp_1", workshopCode=None)
    with caplog.at_level("WARNING"):
        media_id = asyncio.run(keeping.routes._keep_dictation(b"\x00" * 64, _Clip(), dict(_HEARD), _person("DESIGNER"), record))
    assert media_id is None
    assert keeping.seen.created == [] and keeping.seen.stored == []
    assert "dabu" not in caplog.text, "a log line never carries the words"


def test_nothing_is_kept_for_a_clip_with_no_words(keeping):
    record = SimpleNamespace(id="wsp_1", workshopCode=None)
    for result in ({"status": "EMPTY", "text": ""}, {"status": "FAILED", "text": None}, {"status": "COMPLETED", "text": "  "}):
        assert asyncio.run(keeping.routes._keep_dictation(b"\x00", _Clip(), result, _person("DESIGNER"), record)) is None
    assert keeping.seen.puts == [] and keeping.seen.created == []

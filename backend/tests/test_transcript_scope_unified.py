import asyncio
from types import SimpleNamespace

from app.services import records
from app.services.records import (
    MediaUrlOwners,
    TranscriptScope,
    media_url_allows,
    media_withheld,
    transcript_withheld,
)


def _file(uploader: str, **fields: str) -> dict[str, str]:
    return {"id": f"m-{uploader}-{len(fields)}", "uploadedById": uploader, **fields}


def test_media_withheld_is_the_negation_of_media_url_allows_for_scoped_grants() -> None:
    owners = MediaUrlOwners({"viewer"}, {"owner": frozenset({"art-1"})})
    shared = _file("owner", artisanId="art-1")
    unshared = _file("owner", artisanId="art-2")
    own = _file("viewer")
    for node in (shared, unshared, own):
        assert media_withheld(node, owners) is not media_url_allows(owners, node)
    assert not media_withheld(shared, owners)
    assert media_withheld(unshared, owners)
    assert not media_withheld(own, owners)


def test_everyone_sees_everything_when_unscoped() -> None:
    assert not media_withheld(_file("anyone"), None)
    assert not transcript_withheld(_file("anyone"), None)


def test_transcript_scope_carries_scoped_grants() -> None:
    scope = TranscriptScope(frozenset({"viewer"}), frozenset(), (("owner", frozenset({"prod-9"})),))
    assert not transcript_withheld(_file("owner", productId="prod-9"), scope)
    assert transcript_withheld(_file("owner", productId="prod-8"), scope)
    assert transcript_withheld(_file("stranger", productId="prod-9"), scope)
    assert not transcript_withheld(_file("viewer"), scope)


def test_transcript_scope_sql_binds_each_scoped_owner() -> None:
    params: list[object] = []

    def bind(value: object) -> str:
        params.append(value)
        return f"${len(params)}"

    scope = TranscriptScope(
        frozenset({"viewer"}),
        frozenset({"ws-1"}),
        (("owner", frozenset({"art-1", "prod-2"})), ("empty", frozenset())),
    )
    clause = scope.sql("m", bind)
    assert 'm."uploadedById" = ANY($1::text[])' in clause
    assert 'm."linkedRecordId" = ANY($2::text[])' in clause
    assert 'm."uploadedById" = $4' in clause
    assert 'm."artisanId" = ANY($5::text[])' in clause
    assert 'm."questionnaireInterviewId" = ANY($5::text[])' in clause
    assert "owner" in params
    assert ["art-1", "prod-2"] in params
    assert "empty" not in params


def test_transcript_scope_sql_without_scoped_grants_is_the_privacy_rule() -> None:
    params: list[object] = []

    def bind(value: object) -> str:
        params.append(value)
        return f"${len(params)}"

    clause = TranscriptScope(frozenset({"viewer"}), frozenset()).sql("tm", bind)
    assert clause.count(" OR ") == 1
    assert len(params) == 3


def test_readable_ids_skip_removed_files(monkeypatch) -> None:
    async def unscoped(viewer: object) -> None:
        return None

    monkeypatch.setattr(records, "transcript_scope", unscoped)
    rows = [
        SimpleNamespace(id="live", removedAt=None),
        SimpleNamespace(id="gone", removedAt="2026-11-01T00:00:00Z"),
        SimpleNamespace(id=None, removedAt=None),
    ]
    assert asyncio.run(records.transcript_readable_ids(rows, object())) == {"live"}

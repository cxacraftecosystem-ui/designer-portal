from __future__ import annotations

from types import SimpleNamespace
from typing import Any


class LedgerDb:
    def __init__(self) -> None:
        self.rows: dict[str, dict[str, str]] = {"ARTISAN": {}, "RECORDING": {}, "WORKSHOP": {}}
        self.media_artisans: dict[str, list[str]] = {}
        self.participants: dict[str, list[str]] = {}
        self.interviews: dict[str, Any] = {}
        self.workshops: dict[str, Any] = {}
        self.workshop_artisans: dict[str, list[str]] = {}
        self.media: dict[str, Any] = {}
        self.layers: dict[str, Any] = {}
        self.broken = False
        self.appended: list[tuple[Any, ...]] = []
        self.questionnaireinterview = SimpleNamespace(find_unique=self._interview)
        self.designworkshop = SimpleNamespace(find_unique=self._workshop)
        self.mediafile = SimpleNamespace(find_unique=self._media)
        self.dwailayer = SimpleNamespace(find_unique=self._layer)

    async def _media(self, where: dict[str, Any]) -> Any:
        return self.media.get(where["id"])

    async def _layer(self, where: dict[str, Any]) -> Any:
        return self.layers.get(where["id"])

    async def _interview(self, where: dict[str, Any]) -> Any:
        return self.interviews.get(where["id"])

    async def _workshop(self, where: dict[str, Any]) -> Any:
        return self.workshops.get(where["id"])

    async def query_raw(self, sql: str, *args: Any) -> list[dict[str, Any]]:
        if self.broken:
            raise ConnectionError("the database cannot be reached")
        if "consent_current_v" in sql:
            subject_type, _purpose, ids = args
            known = self.rows.get(subject_type, {})
            return [{"subjectId": i, "state": known[i], "recordedAt": None} for i in ids if i in known]
        if 'FROM "MediaFile" m' in sql:
            return [{"a": a} for a in self.media_artisans.get(args[0], [])]
        if '"QuestionnaireInterviewArtisan"' in sql:
            return [{"artisanId": a} for a in self.participants.get(args[0], [])]
        if 'FROM "Artisan"' in sql:
            return [{"a": a} for w in args[0] for a in self.workshop_artisans.get(w, [])]
        if '"DwStageEntry"' in sql:
            return []
        raise AssertionError(f"unexpected statement: {sql[:80]}")

    async def execute_raw(self, sql: str, *args: Any) -> int:
        assert sql.startswith('INSERT INTO "Consent"'), sql[:80]
        _row_id, subject_type, subject_id, _purpose, state = args[:5]
        self.appended.append(args)
        self.rows.setdefault(subject_type, {})[subject_id] = state
        return 1


def workshop(workshop_id: str, consent: str) -> Any:
    return SimpleNamespace(id=workshop_id, dictationConsent=consent, dictationConsentAt=None,
                           dictationConsentById=None)

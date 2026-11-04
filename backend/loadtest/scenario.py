from collections.abc import Callable
from dataclasses import dataclass, field
from typing import Any

SETUP_STAGE = "WORKSHOP_SETUP"
SETUP_ENTITY = "workshopSetup"
PARTICIPANT_STAGE = "WORKSHOP_PLAN_PARTICIPANTS_OPENING"
PARTICIPANT_ENTITY = "participant"


@dataclass(frozen=True)
class Step:
    name: str
    weight: float
    build: Callable[[Session], tuple[str, str, Any]]
    expect: frozenset[int] = frozenset({200})
    needs_workshop: bool = False


@dataclass
class Session:
    index: int
    email: str
    token: str = ""
    user_id: str = ""
    workshop_id: str = ""
    seq: int = 0
    schema_etag: str | None = None
    extra: dict[str, Any] = field(default_factory=dict)

    @property
    def auth(self) -> dict[str, str]:
        return {"Authorization": f"Bearer {self.token}"}



def _sign_in(s: Session) -> tuple[str, str, Any]:
    return "POST", "/api/auth/login", {"email": s.email, "password": LOAD_PASSWORD}


def _dashboard(s: Session) -> tuple[str, str, Any]:
    return "GET", "/api/dashboard/stats", None


def _workshop_list(s: Session) -> tuple[str, str, Any]:
    page = 1 + (s.seq % 5)
    return "GET", f"/api/design-workshops?page={page}&pageSize=20", None


def _workshop_read(s: Session) -> tuple[str, str, Any]:
    return "GET", f"/api/design-workshops/{s.workshop_id}", None


def _record_create(s: Session) -> tuple[str, str, Any]:
    s.seq += 1
    body = {
        "entries": [
            {
                "entityKey": PARTICIPANT_ENTITY,
                "data": {
                    "name": f"Load Participant {s.index}-{s.seq}",
                    "localName": f"लोड {s.index}-{s.seq}",
                },
            }
        ],
        "replaceCollections": False,
    }
    return "PUT", f"/api/design-workshops/{s.workshop_id}/stages/{PARTICIPANT_STAGE}", body


def _questionnaire(s: Session) -> tuple[str, str, Any]:
    return "GET", "/api/questionnaire/questions", None


def _task_list(s: Session) -> tuple[str, str, Any]:
    return "GET", "/api/tasks?view=assigned&page=1&pageSize=20", None


def _search(s: Session) -> tuple[str, str, Any]:
    term = _SEARCH_TERMS[s.seq % len(_SEARCH_TERMS)]
    return "GET", f"/api/search?q={term}&page=1&pageSize=10", None


def _metadata_write(s: Session) -> tuple[str, str, Any]:
    s.seq += 1
    return "PATCH", f"/api/design-workshops/{s.workshop_id}", {
        "notes": f"load-test pass {s.seq} from identity {s.index}"
    }


def _media_presign(s: Session) -> tuple[str, str, Any]:
    s.seq += 1
    return "POST", "/api/media/presign", {
        "filename": f"loadtest-{s.index}-{s.seq}.jpg",
        "mimeType": "image/jpeg",
        "mediaType": "IMAGE",
        "sizeBytes": 1_048_576,
    }


def _schema_fetch(s: Session) -> tuple[str, str, Any]:
    return "GET", "/api/design-workshops/schema", None


def _control(s: Session) -> tuple[str, str, Any]:
    return "GET", "/health", None


_SEARCH_TERMS = ("weave", "clay", "loom", "dye", "brass", "cane", "silk", "block")

LOAD_PASSWORD = "LoadTest123!"

EMAIL_PATTERN = "loadtest+{index:05d}@example.org"


MIX: tuple[Step, ...] = (
    Step("record_create", 8, _record_create, frozenset({200, 422}), needs_workshop=True),
    Step("metadata_write", 6, _metadata_write, frozenset({200}), needs_workshop=True),
    Step("media_presign", 5, _media_presign, frozenset({200})),
    Step("workshop_read", 18, _workshop_read, frozenset({200}), needs_workshop=True),
    Step("dashboard", 14, _dashboard),
    Step("workshop_list", 12, _workshop_list),
    Step("task_list", 10, _task_list),
    Step("questionnaire", 9, _questionnaire),
    Step("search", 8, _search),
    Step("schema_fetch", 7, _schema_fetch, frozenset({200, 304})),
    Step("sign_in", 1, _sign_in, frozenset({200, 429})),
    Step("control", 4, _control),
)

SYNC_BURST: tuple[str, ...] = ("record_create", "record_create", "record_create", "metadata_write")

BY_NAME = {step.name: step for step in MIX}

import os
import socket
import sys
from ipaddress import ip_address
from pathlib import Path
from urllib.parse import urlsplit

import pytest

os.environ.setdefault("QUESTIONNAIRE_V2", "false")

BACKEND_ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(BACKEND_ROOT))

TEST_AADHAAR_FINGERPRINT_KEY = "test-only-aadhaar-fingerprint-key-not-a-secret-0123"
if not os.environ.get("AADHAAR_FINGERPRINT_KEY"):
    os.environ["AADHAAR_FINGERPRINT_KEY"] = TEST_AADHAAR_FINGERPRINT_KEY



def pytest_configure(config: pytest.Config) -> None:
    names = [name for name, _plugin in config.pluginmanager.list_name_plugin()]
    if "anyio" in names and "asyncio" in names and names.index("asyncio") < names.index("anyio"):
        raise pytest.UsageError(
            "pytest-asyncio is registered before anyio's pytest plugin, so async fixtures of "
            "anyio-marked tests would run on a different event loop from their tests. Keep "
            '`addopts = ["-p", "anyio"]` in backend/pyproject.toml, or pass `-p anyio` first on '
            "the command line."
        )

DATABASE_URL_WAS_EXPORTED = bool(os.environ.get("DATABASE_URL"))



def resolve_database_url() -> str:
    try:
        from app.core.config import get_settings

        resolved = str(get_settings().database_url or "")
        if resolved:
            return resolved
    except Exception:  # noqa: BLE001 - no settings at all is not an error here, just less evidence
        pass
    exported = os.environ.get("DATABASE_URL", "")
    if exported:
        return exported
    return _dotenv_database_url()


def _dotenv_database_url() -> str:
    dotenv = BACKEND_ROOT / ".env"
    try:
        lines = dotenv.read_text(encoding="utf-8", errors="replace").splitlines()
    except OSError:
        return ""
    found = ""
    for raw in lines:
        line = raw.strip()
        if line.startswith("DATABASE_URL="):
            found = line.split("=", 1)[1].strip().strip("'\"")
    return found


DATABASE_URL = resolve_database_url()

if DATABASE_URL and not os.environ.get("DATABASE_URL"):
    os.environ["DATABASE_URL"] = DATABASE_URL


def is_local_dsn(url: str) -> bool:
    try:
        host = (urlsplit(url).hostname or "").strip().strip("[]").lower()
    except ValueError:
        return False
    if not host:
        return False
    if host == "localhost" or host.endswith(".localhost"):
        return True
    try:
        return ip_address(host).is_loopback
    except ValueError:
        return False


HAS_LOCAL_DATABASE = is_local_dsn(DATABASE_URL)


def database_is_listening(url: str, timeout: float = 1.0) -> bool:
    parsed = urlsplit(url)
    try:
        host, port = parsed.hostname, parsed.port
    except ValueError:
        return False
    if not host:
        return False
    try:
        with socket.create_connection((host, port or 5432), timeout=timeout):
            return True
    except OSError:
        return False


DATABASE_REACHABLE = HAS_LOCAL_DATABASE and database_is_listening(DATABASE_URL)

needs_db = pytest.mark.skipif(
    not DATABASE_REACHABLE,
    reason=(
        "needs a LOCAL database that is actually running; refuses a remote DATABASE_URL, and skips "
        "when nothing is listening at the configured host and port"
    ),
)



class RemoteDatabaseRefused(BaseException):
    pass


def _refused_host() -> str:
    try:
        return urlsplit(DATABASE_URL).hostname or "(no host)"
    except ValueError:
        return "(unparseable)"


def _refuse_remote_database_connection(*_args: object, **_kwargs: object) -> None:
    raise RemoteDatabaseRefused(
        "refusing to open a database connection: DATABASE_URL resolves to host "
        f"{_refused_host()!r}, which is not a database on this machine, and these tests create and "
        "delete rows. If this test was meant to skip, its own skip predicate disagrees with "
        "conftest.is_local_dsn — replace it with `from conftest import needs_db`."
    )


def _arm_remote_database_refusal() -> bool:
    prisma_module = sys.modules.get("prisma")
    client_class = getattr(prisma_module, "Prisma", None)
    if client_class is None:
        return False
    if getattr(client_class.connect, "__name__", "") != "_refuse_remote_database_connection":
        client_class.connect = _refuse_remote_database_connection
    return True


def pytest_collection_finish(session: pytest.Session) -> None:
    if HAS_LOCAL_DATABASE:
        return
    _arm_remote_database_refusal()


@pytest.fixture(autouse=True)
def _remote_database_refusal_stays_armed() -> None:
    if not HAS_LOCAL_DATABASE:
        _arm_remote_database_refusal()


def _gate_sentence() -> str:
    if DATABASE_REACHABLE:
        return "database: local DSN resolved — database-backed tests WILL run"
    if HAS_LOCAL_DATABASE:
        parsed = urlsplit(DATABASE_URL)
        where = f"{parsed.hostname}:{parsed.port or 5432}"
        return (
            f"database: NOTHING IS LISTENING on {where} — database-backed tests will SKIP. "
            "Start it with `docker compose up -d postgres` from the repository root, and check "
            "POSTGRES_HOST_PORT matches the port in backend/.env."
        )
    if DATABASE_URL:
        return "database: DSN is not local — database-backed tests will SKIP (by design)"
    return "database: none configured — database-backed tests will SKIP (by design)"


def pytest_report_header() -> str:
    return _gate_sentence()


def pytest_terminal_summary(terminalreporter) -> None:
    terminalreporter.write_line("")
    terminalreporter.write_line(_gate_sentence())

    skipped = terminalreporter.stats.get("skipped", [])
    if not skipped:
        return

    tally: dict[str, int] = {}
    for report in skipped:
        longrepr = getattr(report, "longrepr", None)
        if isinstance(longrepr, tuple) and len(longrepr) == 3:
            reason = str(longrepr[2])
        else:
            reason = str(longrepr)
        reason = reason.removeprefix("Skipped: ").strip() or "(no reason given)"
        tally[reason] = tally.get(reason, 0) + 1

    terminalreporter.write_line(f"skipped {len(skipped)} tests, by reason:")
    for reason, count in sorted(tally.items(), key=lambda item: (-item[1], item[0])):
        terminalreporter.write_line(f"  {count:>5} × {reason}")


@pytest.fixture(autouse=True)
def _clear_account_credential_budget():
    from app.scale.rate_limit import reset_account_credential_budget

    reset_account_credential_budget()
    yield
    reset_account_credential_budget()



@pytest.fixture(autouse=True)
def _no_report_survives_a_test():
    import sys

    def forget() -> None:
        cached = sys.modules.get("app.services.data_explorer.report_cache")
        if cached is not None:
            cached.CACHE.clear()

    forget()
    yield
    forget()


@pytest.fixture(autouse=True)
def _fresh_aadhaar_legacy_answer():
    from app.services.aadhaar_fingerprint import reset_legacy_pending

    reset_legacy_pending()
    yield


@pytest.fixture(autouse=True)
def _questionnaire_v2_off_unless_a_test_turns_it_on(monkeypatch: pytest.MonkeyPatch):
    try:
        from app.core.config import get_settings

        settings = get_settings()
    except Exception:  # noqa: BLE001 - no settings in this run: nothing to pin
        yield
        return
    monkeypatch.setattr(settings, "questionnaire_v2", False)
    yield


@pytest.fixture(autouse=True)
def _uploads_reach_storage_unless_a_test_says_otherwise(monkeypatch: pytest.MonkeyPatch):
    try:
        from app.api.routes import media
    except Exception:  # noqa: BLE001
        yield
        return
    monkeypatch.setattr(media, "object_exists", lambda _key: True, raising=False)
    yield

from __future__ import annotations

import json
import os
import subprocess
import sys
from pathlib import Path

import pytest

BACKEND_ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(BACKEND_ROOT / "scripts"))

from generate_prisma_client import SHIM_MARKER  # noqa: E402

ON_DEMAND = ("openpyxl", "reportlab", "PIL", "lxml", "pypdf", "pydub", "anthropic", "openai")

WORKER_THIRD_PARTY = frozenset(
    {
        "annotated_doc",
        "annotated_types",
        "anyascii",
        "anyio",
        "bcrypt",
        "boto3",
        "botocore",
        "certifi",
        "cffi",
        "charset_normalizer",
        "click",
        "cryptography",
        "dateutil",
        "dns",
        "dotenv",
        "email_validator",
        "fastapi",
        "h11",
        "httpcore",
        "httpx",
        "idna",
        "jinja2",
        "jmespath",
        "jwt",
        "markupsafe",
        "nodeenv",
        "opentelemetry",
        "prisma",
        "pydantic",
        "pydantic_core",
        "pydantic_settings",
        "pygments",
        "python_multipart",
        "requests",
        "s3transfer",
        "six",
        "sniffio",
        "starlette",
        "tomlkit",
        "typing_extensions",
        "typing_inspection",
        "urllib3",
    }
)

PLACEHOLDER_ENV = {
    "DATABASE_URL": "postgresql://footprint:footprint@footprint.invalid:5432/footprint",
    "JWT_SECRET": "footprint-placeholder-not-a-secret-0123456789abcdef",
    "AWS_ACCESS_KEY_ID": "footprint-placeholder",
    "AWS_SECRET_ACCESS_KEY": "footprint-placeholder",
    "AWS_REGION": "ap-south-1",
    "AWS_S3_BUCKET": "footprint-placeholder",
    "MASTER_ADMIN_EMAIL": "footprint@example.invalid",
}

FEATURE_FLAGS_OFF = {
    "AADHAAR_OFFLINE_VERIFICATION_ENABLED": "false",
    "AADHAAR_ONLINE_AUTH_ENABLED": "false",
    "AADHAAR_EKYC_ENABLED": "false",
    "AI_FEATURES_ENABLED": "false",
}

_PROBE = """
import json, sys
import {module}
import prisma.types
tops = sorted({{m.split(".")[0] for m in sys.modules}})
print(json.dumps({{
    "tops": [t for t in tops if t not in sys.stdlib_module_names and not t.startswith("_")],
    "app": sorted(m for m in sys.modules if m == "app" or m.startswith("app.")),
    "types_file": prisma.types.__file__,
    "types_names": len(vars(prisma.types)),
}}))
"""


def _imports_of(module: str, extra_env: dict[str, str] | None = None) -> dict:
    env = {**PLACEHOLDER_ENV, **os.environ, **(extra_env or {})}
    completed = subprocess.run(
        [sys.executable, "-c", _PROBE.format(module=module)],
        cwd=BACKEND_ROOT,
        env=env,
        capture_output=True,
        text=True,
        check=False,
        timeout=600,
    )
    assert completed.returncode == 0, completed.stderr[-4000:]
    return json.loads(completed.stdout.strip().splitlines()[-1])


@pytest.fixture(scope="module")
def worker() -> dict:
    return _imports_of("app.worker")


@pytest.fixture(scope="module")
def api() -> dict:
    return _imports_of("app.main", FEATURE_FLAGS_OFF)


def test_prisma_types_at_run_time_is_the_small_shim(worker: dict) -> None:
    types_py = Path(worker["types_file"])
    with types_py.open(encoding="utf-8") as handle:
        first = handle.readline()
    assert first == SHIM_MARKER and types_py.with_suffix(".pyi").exists(), (
        f"{types_py} is the full generated module: every process importing it holds ~1.4 GB. "
        "Regenerate the client with `python scripts/generate_prisma_client.py`."
    )
    assert worker["types_names"] < 200


def test_the_worker_does_not_import_the_web_app(worker: dict) -> None:
    web = [m for m in worker["app"] if m in ("app.main", "app.api") or m.startswith("app.api.")]
    assert web == [], f"python -m app.worker imports the web app: {web[:10]}"
    assert "uvicorn" not in worker["tops"]


def test_the_worker_imports_only_the_pinned_third_party_set(worker: dict) -> None:
    extra = sorted(set(worker["tops"]) - WORKER_THIRD_PARTY - {"app", "cython_runtime"})
    assert extra == [], (
        f"python -m app.worker now imports {extra} at start. Import it inside the function that "
        "needs it, or add it to WORKER_THIRD_PARTY in this file with the reason it must be resident."
    )


@pytest.mark.parametrize("process", ["worker", "api"])
def test_on_demand_libraries_are_not_imported_at_start(
    process: str, request: pytest.FixtureRequest
) -> None:
    loaded = [name for name in ON_DEMAND if name in request.getfixturevalue(process)["tops"]]
    assert loaded == [], (
        f"starting the {process} imports {loaded}, which only on-demand features use (and, for "
        "lxml, only switched-off Aadhaar features). Move the import into the function that needs it."
    )

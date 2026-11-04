import re
from pathlib import Path

from packaging.specifiers import SpecifierSet
from packaging.version import Version

ROOT = Path(__file__).resolve().parents[2]
DEPLOY = ROOT / ".github" / "workflows" / "deploy-backend.yml"
USER_DATA = ROOT / "infra" / "terraform" / "user_data.sh"
CHECKS = ROOT / ".github" / "workflows" / "checks.yml"
PYPROJECT = ROOT / "backend" / "pyproject.toml"

KEYS = ("VERSION", "BUILD", "URL", "SHA256")
EC2_USER_DATA_LIMIT = 16384


def _pins(text: str, pattern: str, where: str) -> dict:
    found = {}
    for key, value in re.findall(pattern, text, re.MULTILINE):
        assert key not in found, f"BOX_PYTHON_{key} is set twice in {where}"
        found[key] = value
    missing = [k for k in KEYS if k not in found]
    assert not missing, f"{where} sets no BOX_PYTHON_{', BOX_PYTHON_'.join(missing)}"
    return found


def _deploy_pins() -> dict:
    return _pins(
        DEPLOY.read_text(encoding="utf-8"),
        r'^\s+BOX_PYTHON_(VERSION|BUILD|URL|SHA256):\s*"([^"]*)"\s*$',
        "deploy-backend.yml",
    )


def _user_data_pins() -> dict:
    return _pins(
        USER_DATA.read_text(encoding="utf-8"),
        r'^BOX_PYTHON_(VERSION|BUILD|URL|SHA256)="([^"]*)"$',
        "user_data.sh",
    )


def test_the_workflow_and_the_boot_script_pin_the_same_build():
    deploy, boot = _deploy_pins(), _user_data_pins()
    assert deploy == boot, (
        "deploy-backend.yml and infra/terraform/user_data.sh pin different interpreters — change "
        "the four BOX_PYTHON_* values in both in one commit."
        f"\n  workflow:     {deploy}\n  user_data.sh: {boot}"
    )


def test_the_pin_is_one_fact_written_four_ways():
    pin = _deploy_pins()
    version, build = pin["VERSION"], pin["BUILD"]
    assert re.fullmatch(r"3\.\d+\.\d+", version), (
        f"BOX_PYTHON_VERSION {version!r} is not a final CPython 3.x.y"
    )
    assert re.fullmatch(r"\d{8}", build), (
        f"BOX_PYTHON_BUILD {build!r} is not a python-build-standalone date tag"
    )
    assert pin["URL"] == (
        "https://github.com/astral-sh/python-build-standalone/releases/download/"
        f"{build}/cpython-{version}%2B{build}-x86_64-unknown-linux-gnu-install_only.tar.gz"
    ), "BOX_PYTHON_URL must be that release's install_only x86_64-unknown-linux-gnu build"
    assert re.fullmatch(r"[0-9a-f]{64}", pin["SHA256"]), (
        "BOX_PYTHON_SHA256 is not a lowercase SHA-256"
    )


def test_both_files_install_into_the_same_versioned_directory():
    home = "/opt/cpython/${BOX_PYTHON_VERSION}+${BOX_PYTHON_BUILD}"
    for path in (DEPLOY, USER_DATA):
        assert home in path.read_text(encoding="utf-8"), (
            f"{path.name} no longer installs into {home}"
        )


def test_both_files_byte_compile_the_standard_library_before_the_rename():
    for path in (DEPLOY, USER_DATA):
        text = path.read_text(encoding="utf-8")
        compile_at = text.find("-m compileall")
        rename_at = text.find('mv -T "$stage/python"')
        assert compile_at != -1, (
            f"{path.name} no longer byte-compiles the pinned interpreter's standard library"
        )
        assert rename_at != -1 and compile_at < rename_at, (
            f"{path.name} must byte-compile in the staging directory, before the rename into place"
        )


def test_no_apt_source_is_asked_for_python_any_more():
    for path in (DEPLOY, USER_DATA):
        text = path.read_text(encoding="utf-8")
        assert "add-apt-repository" not in text, f"{path.name} adds an apt repository again"
        assert "ppa:deadsnakes" not in text, (
            f"{path.name} names the deadsnakes PPA as a source again"
        )
        assert not re.search(r"apt-get[^\n]*\binstall\b[^\n]*\bpython3\.\d+", text), (
            f"{path.name} installs a python3.x package from apt again"
        )


def test_the_runner_builds_the_release_venv_with_the_boxs_exact_build():
    text = DEPLOY.read_text(encoding="utf-8")
    runner = text[text.index("- name: Install the pinned CPython on the runner") :]
    runner = runner[: runner.index("- name: Ship the runner-built venv")]
    assert '[ "$got" = "$BOX_PYTHON_SHA256" ]' in runner, (
        "the runner's interpreter is not checked against BOX_PYTHON_SHA256"
    )
    assert 'home="/opt/cpython/${BOX_PYTHON_VERSION}+${BOX_PYTHON_BUILD}"' in runner
    assert 'py="/opt/cpython/${BOX_PYTHON_VERSION}+${BOX_PYTHON_BUILD}/bin/python${minor}"' in runner
    assert '"$py" -m venv .venv' in runner, (
        "the release venv is not built by the pinned interpreter at the box's path"
    )
    assert "actions/setup-python" not in runner, (
        "setup-python builds its own CPython, not the box's python-build-standalone one"
    )


def test_the_pinned_version_is_one_this_project_supports_and_ci_tests():
    version = Version(_deploy_pins()["VERSION"])
    requires = re.search(
        r'^requires-python\s*=\s*"([^"]+)"', PYPROJECT.read_text(encoding="utf-8"), re.MULTILINE
    )
    assert requires, "backend/pyproject.toml has no requires-python"
    assert version in SpecifierSet(requires.group(1)), (
        f"the box is pinned to {version}, outside backend/pyproject.toml's requires-python "
        f"{requires.group(1)}"
    )
    minor = f"{version.major}.{version.minor}"
    ci = re.findall(
        r'^\s+python-version:\s*"([^"]+)"', CHECKS.read_text(encoding="utf-8"), re.MULTILINE
    )
    assert ci, "checks.yml sets no python-version"
    assert all(v == minor or v.startswith(minor + ".") for v in ci), (
        f"checks.yml tests {ci} while the box is pinned to {version}: CI must test the minor "
        "production runs"
    )


def test_the_boot_script_fits_in_ec2_user_data():
    size = len(USER_DATA.read_bytes())
    assert size <= EC2_USER_DATA_LIMIT, (
        f"infra/terraform/user_data.sh is {size} bytes; EC2 refuses user data over "
        f"{EC2_USER_DATA_LIMIT}, so a rebuild would fail at `terraform plan`. Shorten its comments."
    )

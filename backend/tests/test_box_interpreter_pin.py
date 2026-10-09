"""THE API BOX'S INTERPRETER IS PINNED IN TWO FILES, AND THEY MUST AGREE.

Since 2026-10-09 every release venv on the EC2 box is built from upstream CPython, installed from
a python-build-standalone release and checked against a pinned SHA-256 before it is unpacked — not
from Ubuntu's python3.14 (3.14.4 on 26.04) and not from the deadsnakes PPA. The pin is four values:
the CPython version, the python-build-standalone release (a date tag) that carries it, that
release's asset URL, and the asset's SHA-256 from the release's SHA256SUMS. They are written twice,
on purpose:

* ``.github/workflows/deploy-backend.yml`` (``BOX_PYTHON_*`` in the deploy job's env), which
  installs the interpreter on every deploy and builds the release venv from it, and
* ``infra/terraform/user_data.sh``, which installs the same build at a rebuilt box's first boot,
  before any deploy has run — the file cannot read the workflow, so it carries its own copy.

A bump that reaches one file and not the other gives a rebuilt box one interpreter at first boot and
another at its first deploy, and the deploy's venv step then builds every release on a different
directory than the one the box was provisioned with. Nothing on the box would fail; it would just
quietly stop being the configuration anybody reviewed. So this reads both files and fails the suite
when they disagree, when the URL does not name the version and the release it sits beside, or when
the digest is not a SHA-256 — the same checks the deploy's install step makes on the box, made here
where a mistake costs a red test rather than a refused deploy.

It also holds two limits that nothing else in CI checks: EC2's 16 KB cap on user data (`terraform
validate` enforces it, and no workflow runs terraform), and that CI's backend jobs test the same
minor the box runs. Reads files only; needs no database and imports nothing from ``app``.
"""

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
# EC2 refuses user data over 16 KB, measured before base64; the AWS provider's validation is the
# same number in bytes.
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


def test_no_apt_source_is_asked_for_python_any_more():
    # The deadsnakes PPA was the 24.04 source until 2026-10-09; comments may still say why it went.
    for path in (DEPLOY, USER_DATA):
        text = path.read_text(encoding="utf-8")
        assert "add-apt-repository" not in text, f"{path.name} adds an apt repository again"
        assert "ppa:deadsnakes" not in text, (
            f"{path.name} names the deadsnakes PPA as a source again"
        )
        assert not re.search(r"apt-get[^\n]*\binstall\b[^\n]*\bpython3\.\d+", text), (
            f"{path.name} installs a python3.x package from apt again"
        )


def test_the_runner_side_generator_runs_the_boxs_exact_version():
    text = DEPLOY.read_text(encoding="utf-8")
    assert "python-version: ${{ env.BOX_PYTHON_VERSION }}" in text, (
        "the generator's setup-python must take BOX_PYTHON_VERSION, so the client the box imports "
        "is generated by the version it runs"
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

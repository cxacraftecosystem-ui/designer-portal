import app.services.stage_definitions  # noqa: F401  - installs the registry the router imports
from app.main import app as application


def _paths() -> set[str]:
    return set(application.openapi()["paths"])


def test_there_is_exactly_one_sign_in_endpoint():
    paths = _paths()
    assert "/api/auth/login" in paths, "the sign-in endpoint both clients call has moved or gone"
    assert "/api/auth/google" not in paths, (
        "POST /auth/google is back. It is an alias for /auth/login that no client calls, and the "
        "reason it was removed is in the comment at the top of `login`: the admission rules below "
        "that line get added to the door people use. If an external consumer really needs this "
        "path, give it a docstring naming that consumer — do not re-add it silently."
    )
    assert not any(path.endswith("/google") for path in paths), sorted(
        path for path in paths if path.endswith("/google")
    )


def test_the_two_mounts_of_me_are_still_both_there():
    paths = _paths()
    assert "/api/me" in paths
    assert "/api/auth/me" in paths

"""How many doors the sign-in has, and which of them anything actually knocks on.

There were two. ``POST /auth/login`` — what `AuthProvider.loginWithGoogle` and
`WorkshopRepositoryApi`'s two `@POST("auth/login")` declarations both call — and ``POST
/auth/google``, four lines that rejected a body with no ``googleIdToken`` and then called straight
into ``login``. Nothing in the frontend, the e2e suite or the Android tree referenced the second
one, and no comment named a legacy consumer it was being kept for.

WHY THAT IS WORTH A TEST RATHER THAN A DELETION AND A SHRUG. Everything after the credential check
in ``login`` is admission policy: the platform allow-list, the designer roster's own refusal
sentence, the "first seen" stamp an administrator reads as "the invitation was accepted". Those
have all been added since the alias was written, and each was added to ``login``. A second entry
point that nobody exercises and no test covers does not stay in step with that by itself — it
stays in step until the first person who does not know it is there, and then the two doors admit
different people. That is the failure this pins shut: not the alias as it stood, which was
harmless, but the next refusal that reaches one door and not the other.

Deliberately reads the ROUTING TABLE rather than making requests: the question is which paths
exist, which is a property of the app object and needs no database, no client and no fixtures.

Through the OpenAPI document rather than through ``app.routes``, and that is not decoration —
``app.routes`` on this FastAPI version holds SEVEN entries, of which one is a lazy
``_IncludedRouter`` standing in for the entire ``/api`` tree. A test walking that list finds
``/docs`` and ``/health`` and concludes that no endpoint in this application exists, which is the
shape of assertion that passes for years while checking nothing.
"""

# `application`, not `app`: the first import binds `app` to the PACKAGE, and rebinding that name to
# the FastAPI instance leaves a file where `app.anything` means two different objects depending on
# which line you are reading.
import app.services.stage_definitions  # noqa: F401  - installs the registry the router imports
from app.main import app as application


def _paths() -> set[str]:
    return set(application.openapi()["paths"])


def test_there_is_exactly_one_sign_in_endpoint():
    """``/api/auth/login`` takes both credentials; nothing else may take either."""
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
    """The contrast case, so this file is not read as "one handler, one path, always".

    ``GET /api/me`` is a SECOND, deliberate mount of ``auth.me`` (``api_router.add_api_route`` in
    ``app.api.router``) and is what ``AuthProvider.refreshMe`` and Android's ``@GET("me")`` call —
    while ``/api/auth/me`` is the same handler under the auth prefix. Two paths, one function, no
    policy of their own to drift: that is what a legitimate alias looks like, and deleting one of
    these WOULD break a client. ``/auth/google`` was not this case.
    """
    paths = _paths()
    assert "/api/me" in paths
    assert "/api/auth/me" in paths

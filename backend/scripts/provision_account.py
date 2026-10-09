"""Provision ONE account from the command line, under the rules ``POST /api/users`` obeys. Dry run.

WHY THIS EXISTS. An operator sometimes has to create an account with no browser in reach — over
Session Manager on the API box, for the first ministry accounts on a fresh deployment. The two
shortcuts available there are both wrong: ``seed_admin.py`` touches only the master admin, and SQL
skips everything that makes an account usable (``passwordSetAt``, ``mustChangePassword``, the
allow-list admission, the designer empanelment) and every rule about who may create whom. So this
script does not have a rule of its own. It calls ``app/services/account_provisioning.py`` — the
module ``routes/users.create_user`` calls — as the account named by ``--actor-email``, and it refuses
exactly what the route would refuse that account.

TWO MODES

  (default)       A LOCAL account with a password, exactly as ``POST /api/users`` makes one:
                  ``account_provisioning.plan_account`` then ``write_account``. The password is read
                  from the ``PROVISION_PASSWORD`` environment variable and from nowhere else: there
                  is no flag for it, because a command line lands in shell history and in the process
                  table, and it is never printed. ``mustChangePassword`` is set unless
                  ``--no-must-change`` is given — a password the operator chose is a shared secret.

  --google-only   NO account. An ACTIVE allow-list row for the address at ``--role`` with ``--name``,
                  written the way ``POST /api/access/roster`` writes one (``access_roster.admit``, via
                  ``account_provisioning.plan_google_admission`` / ``apply_google_admission``), so the
                  person's first Google sign-in creates the account at that tier. That route is
                  Admin-only, so this mode is too. It refuses an address that already has an
                  account (Google would sign in to that one and the tier would never apply) and an
                  address an administrator barred (the command line does not overturn a bar). A row
                  the mailbox already has — under any spelling — is the row written, and the line
                  printed names it by its own spelling; a new row is written under the mailbox.

THE ACTOR IS A REAL ACCOUNT, AND IT HAS TO BE ABLE TO REACH THE ROUTE. ``--actor-email`` names an
existing account; its id is recorded on the allow-list row and the empanelment exactly as the route
records the signed-in provisioner. An actor the API would turn away — barred on the allow-list, not
yet admitted, or still holding a password somebody else chose — is refused here too, because a
script that let it act would be the one door its refusal does not close.

DRY RUN BY DEFAULT. Without ``--apply`` it reads, decides, prints what it WOULD do and writes
nothing. It prints ONE summary line either way, naming the database HOST (never the connection
string) so an operator can see which deployment they are pointed at. Exit status: 0 done or
planned, 1 refused, 2 a usage error.

Usage, from ``backend/`` (PowerShell)::

    $env:PYTHONUTF8 = "1"
    $secure = Read-Host -AsSecureString "Password"                       # typed, not echoed
    $env:PROVISION_PASSWORD = [Net.NetworkCredential]::new("", $secure).Password
    python -m scripts.provision_account --email person@handicrafts.gov.in --name "A Person" `
        --role ASSISTANT_DIRECTOR --actor-email admin@example.org            # DRY RUN
    python -m scripts.provision_account ... --apply                          # writes
    Remove-Item Env:PROVISION_PASSWORD

    python -m scripts.provision_account --google-only --email someone@gmail.com --name "Some One" `
        --role DESIGNER --actor-email admin@example.org --apply

On the API box (Linux, over Session Manager), from the deployed ``backend/`` with its virtualenv::

    read -rs PROVISION_PASSWORD && export PROVISION_PASSWORD     # typed, not echoed
    python -m scripts.provision_account --email ... --name ... --role ... --actor-email ...
    python -m scripts.provision_account ... --apply
    unset PROVISION_PASSWORD

**ON WINDOWS**, keep UTF-8 mode on, as above: Prisma's config loader reads ``backend/pyproject.toml``
with the interpreter's default codec and dies inside ``connect`` without it. :func:`_connect` says so.
"""

import argparse
import asyncio
import os
import re
import sys
from collections.abc import Mapping, Sequence
from typing import Any, NoReturn, TextIO
from urllib.parse import urlsplit

from fastapi import HTTPException
from pydantic import EmailStr, TypeAdapter, ValidationError

from app.core.config import get_settings
from app.core.db import connect_db, db, disconnect_db
from app.core.deps import ROLE_RANK, is_break_glass_master, password_change_pending
from app.schemas.users import UserCreate
from app.services import access_roster, account_provisioning

#: The environment variable the password is read from: the ONE place it may come from.
#:
#: NAMED FOR WHERE THE PASSWORD IS, NOT FOR WHAT IT IS (2026-10-09). As ``PASSWORD_ENV``, every line
#: telling an operator which variable to set — the refusals, the argparse complaint — read to a
#: scanner that judges a value by its identifier (CodeQL's py/clear-text-logging-sensitive-data) as a
#: line printing the password, and so did ``plan.must_change_password`` in the summary, which is now
#: printed as a word. Nothing here prints the password: the value read from this variable goes into
#: ``UserCreate`` and nowhere else.
ENV_VARIABLE = "PROVISION_PASSWORD"

EXIT_OK = 0
EXIT_REFUSED = 1
EXIT_USAGE = 2

#: How the allow-list note on a command-line account says where it was made.
VIA = "from the command line (scripts/provision_account.py)"


class Refused(Exception):
    """A refusal of the script's own, before any route rule is asked."""


#: argparse's complaints whose only variable part is the names of this parser's own options, joined
#: the way argparse joins them — "the following arguments are required: --email, --name", "argument
#: --email: expected one argument". They quote nothing typed, and they are the ones that tell an
#: operator what to fix, so they are repeated word for word. Matched whole, with every name checked
#: against the parser, so a sentence that merely begins the same way cannot carry a value through.
_OPTIONS_ONLY_COMPLAINTS = (
    re.compile(r"the following arguments are required: (?P<options>.+)"),
    re.compile(r"argument (?P<options>\S+): expected one argument"),
)

#: What the operator reads in place of every other complaint. One fixed sentence, because the
#: complaint it replaces may quote a password; it names where the password does go, since a password
#: typed as an argument is the likeliest way to be here.
UNREADABLE_ARGUMENTS = (
    "the arguments could not be read, and what was typed is not repeated here in case it was a "
    f"password (the password is read from {ENV_VARIABLE} only). Run with --help for the arguments "
    "this script takes."
)


class _Parser(argparse.ArgumentParser):
    """argparse, minus every habit that would print a secret.

    Most of argparse's complaints quote what was typed back — an argument it does not know, an
    abbreviation two options share (``--a=…``), a choice it does not offer (``--role …``), a value
    given to a flag (``--apply=…``) — and the value most likely to be typed where it does not belong
    here is a password somebody tried to pass on the command line. It has already reached their shell
    history; it must not reach a terminal log as well. So a complaint is repeated only when it names
    nothing but this parser's options (:data:`_OPTIONS_ONLY_COMPLAINTS`), and every other one becomes
    :data:`UNREADABLE_ARGUMENTS`. Unrecognised arguments were the only case handled until 2026-10-09.

    Its complaints go to ``err`` rather than to ``sys.stderr`` directly, so a caller can collect them
    without swapping ``sys.stderr`` out — which must not be done around this script: Prisma starts its
    query engine with ``stderr=sys.stderr``, and a stream with no file descriptor makes every connect
    fail.
    """

    def __init__(self, *args: Any, err: TextIO, **kwargs: Any) -> None:
        super().__init__(*args, **kwargs)
        self._err = err

    def error(self, message: str) -> NoReturn:
        if not self._names_only_options(message):
            message = UNREADABLE_ARGUMENTS
        self._err.write(self.format_usage())
        self._err.write(f"{self.prog}: error: {message}\n")
        raise SystemExit(EXIT_USAGE)

    def _names_only_options(self, message: str) -> bool:
        for complaint in _OPTIONS_ONLY_COMPLAINTS:
            found = complaint.fullmatch(message)
            if found:
                named = re.split(r", |/", found["options"])
                return all(option in self._option_string_actions for option in named)
        return False


def _parser(err: TextIO) -> argparse.ArgumentParser:
    parser = _Parser(
        prog="python -m scripts.provision_account",
        description=(
            "Create one account under the rules POST /api/users obeys (or, with --google-only, "
            "admit one address for Google sign-in). Dry run unless --apply. The password is read "
            f"from the {ENV_VARIABLE} environment variable only."
        ),
        err=err,
    )
    parser.add_argument("--email", required=True, help="the new account's address")
    parser.add_argument("--name", required=True, help="the name the account is created with")
    parser.add_argument(
        "--role",
        required=True,
        choices=sorted(ROLE_RANK, key=ROLE_RANK.__getitem__),
        help="the tier to create it at; at or below the actor's own",
    )
    parser.add_argument(
        "--actor-email",
        required=True,
        help="the existing provisioner this is done as; recorded as the actor, as the route would",
    )
    parser.add_argument(
        "--no-must-change",
        action="store_true",
        help="do NOT require a new password at the first sign-in (password mode only)",
    )
    parser.add_argument(
        "--google-only",
        action="store_true",
        help="create no account: admit the address for Google sign-in at --role instead",
    )
    parser.add_argument("--apply", action="store_true", help="write; without it nothing is written")
    return parser


def _describe(error: ValidationError) -> str:
    """The validation failure WITHOUT the input. pydantic's own message quotes the value it refused,
    and for the password field that value is the secret."""
    return "; ".join(
        f"{'.'.join(str(part) for part in issue['loc']) or 'body'}: {issue['msg']}"
        for issue in error.errors(include_url=False, include_input=False, include_context=False)
    )


def _database_host() -> str:
    """The host the run is pointed at, and nothing else of the DSN — which carries a password."""
    try:
        return urlsplit(get_settings().database_url).hostname or "an unnamed host"
    except ValueError:
        return "an unparseable host"


async def _connect() -> None:
    """``connect_db`` plus the one Windows failure that does not name itself — see the module note."""
    try:
        await connect_db()
    except UnicodeDecodeError as exc:
        raise Refused(
            "could not connect: Prisma's config loader could not decode backend/pyproject.toml with "
            f"this interpreter's default codec ({exc.encoding}). Re-run with PYTHONUTF8=1."
        ) from exc


async def _actor(email: str) -> Any:
    """The account this is done as — and only if it could have done it through the API."""
    actor = await db.user.find_first(
        where={"email": {"equals": email.strip().lower(), "mode": "insensitive"}}
    )
    if actor is None:
        raise Refused(
            f"no account has the address {email}; --actor-email must name an existing one"
        )
    if not is_break_glass_master(actor):
        row = await access_roster.access_row(actor.email)
        if not access_roster.admits(row):
            raise Refused(
                f"{actor.email} is not admitted on the allow-list "
                f"({access_roster.status_of(row) or 'no row'}), so it could not sign in to do this"
            )
    if password_change_pending(actor):
        raise Refused(
            f"{actor.email} must choose its own password before it can act; the API refuses it "
            "everything else until then"
        )
    return actor


def _warnings(plan: Any) -> str:
    notes = []
    if getattr(plan, "barred", None):
        notes.append(f"re-admits an address the allow-list held as {plan.barred}")
    if getattr(plan, "empanelment_suspended", False):
        notes.append(
            "the address's designer empanelment is suspended, so this designer cannot sign in "
            "until an admin restores it"
        )
    return "".join(f" WARNING: {note}." for note in notes)


async def _provision(args: argparse.Namespace, payload: UserCreate | None, say: Any) -> int:
    host = _database_host()
    actor = await _actor(args.actor_email)
    who = f"as {actor.email} ({actor.id})"
    if args.google_only:
        plan = await account_provisioning.plan_google_admission(
            actor, args.email, role=args.role, full_name=args.name
        )
        verb = (
            "add"
            if plan.existing_status is None
            else f"re-admit the {plan.existing_status} row for"
        )
        if not args.apply:
            say(
                f"DRY RUN on {host}: would {verb} {plan.email} on the allow-list as ACTIVE "
                f"{plan.role} ({plan.full_name!r}) for Google sign-in, {who}; no account exists "
                f"until they sign in with Google.{_warnings(plan)} Nothing was written; re-run "
                "with --apply."
            )
            return EXIT_OK
        row = await account_provisioning.apply_google_admission(actor, plan)
        say(
            f"ADMITTED on {host}: allow-list row {row.id} for {plan.email} is ACTIVE at {plan.role}; "
            f"their first Google sign-in creates the account. Done {who}.{_warnings(plan)}"
        )
        return EXIT_OK

    assert payload is not None
    plan = await account_provisioning.plan_account(actor, payload)
    # A word the flag picks, never the flag — see ENV_VARIABLE. The audit line says it the same way.
    forced_change = "required" if plan.must_change_password else "not required"
    if not args.apply:
        say(
            f"DRY RUN on {host}: would create a {plan.role} password account for {plan.email} "
            f"({plan.name!r}), mustChangePassword={forced_change}, {who}."
            f"{_warnings(plan)} Nothing was written; re-run with --apply."
        )
        return EXIT_OK
    user = await account_provisioning.write_account(actor, plan, payload.password, via=VIA)
    say(
        f"CREATED on {host}: {plan.role} account {user.id} for {plan.email}, "
        f"mustChangePassword={forced_change}, {who}.{_warnings(plan)}"
    )
    return EXIT_OK


async def run(
    argv: Sequence[str] | None = None,
    *,
    environ: Mapping[str, str] | None = None,
    out: TextIO | None = None,
    err: TextIO | None = None,
) -> int:
    """The whole script, callable from a test with its own arguments, environment and streams.

    ``out`` takes the one summary line; ``err`` takes argparse's complaints. Pass streams here rather
    than swapping ``sys.stdout``/``sys.stderr`` around the call: the database client hands those two
    to the query-engine process it starts, and a stream with no file descriptor fails every connect.
    """
    env = os.environ if environ is None else environ
    stream = out or sys.stdout

    def say(line: str) -> None:
        stream.write(line + "\n")

    try:
        args = _parser(err or sys.stderr).parse_args(argv)
    except SystemExit as exc:  # the parser has already said why
        return EXIT_OK if not exc.code else EXIT_USAGE

    password = env.get(ENV_VARIABLE) or ""
    payload: UserCreate | None = None
    if args.google_only:
        if password:
            say(f"REFUSED: --google-only sets no password; unset {ENV_VARIABLE} and run it again.")
            return EXIT_USAGE
        if args.no_must_change:
            say("REFUSED: --no-must-change applies to password accounts, not to --google-only.")
            return EXIT_USAGE
        try:
            TypeAdapter(EmailStr).validate_python(args.email)
        except ValidationError as exc:
            say(f"REFUSED: {_describe(exc)}")
            return EXIT_REFUSED
        if not args.name.strip():
            say("REFUSED: --name is empty.")
            return EXIT_REFUSED
    else:
        if not password:
            say(
                f"REFUSED: set the password in the {ENV_VARIABLE} environment variable. It is never "
                "read from the command line."
            )
            return EXIT_USAGE
        try:
            # The route's own body model, so every length and address rule is the one the API
            # enforces, and the password is kept exactly as typed — never trimmed.
            payload = UserCreate(
                email=args.email,
                name=args.name,
                password=password,
                role=args.role,
                mustChangePassword=not args.no_must_change,
            )
        except ValidationError as exc:
            say(f"REFUSED: {_describe(exc)}")
            return EXIT_REFUSED

    try:
        await _connect()
        return await _provision(args, payload, say)
    except HTTPException as exc:
        say(f"REFUSED ({exc.status_code}): {exc.detail}")
        return EXIT_REFUSED
    except Refused as exc:
        say(f"REFUSED: {exc}")
        return EXIT_REFUSED
    finally:
        await disconnect_db()


def main() -> None:
    # An unencodable character in a name must degrade to "?" rather than end the run between the
    # write and the line that reports it.
    for stream in (sys.stdout, sys.stderr):
        reconfigure = getattr(stream, "reconfigure", None)
        if reconfigure is not None:
            reconfigure(errors="replace")
    raise SystemExit(asyncio.run(run()))


if __name__ == "__main__":
    main()

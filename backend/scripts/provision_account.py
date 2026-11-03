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

ENV_VARIABLE = "PROVISION_PASSWORD"

EXIT_OK = 0
EXIT_REFUSED = 1
EXIT_USAGE = 2

VIA = "from the command line (scripts/provision_account.py)"


class Refused(Exception):
    pass


_OPTIONS_ONLY_COMPLAINTS = (
    re.compile(r"the following arguments are required: (?P<options>.+)"),
    re.compile(r"argument (?P<options>\S+): expected one argument"),
)

UNREADABLE_ARGUMENTS = (
    "the arguments could not be read, and what was typed is not repeated here in case it was a "
    f"password (the password is read from {ENV_VARIABLE} only). Run with --help for the arguments "
    "this script takes."
)


class _Parser(argparse.ArgumentParser):
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
    return "; ".join(
        f"{'.'.join(str(part) for part in issue['loc']) or 'body'}: {issue['msg']}"
        for issue in error.errors(include_url=False, include_input=False, include_context=False)
    )


def _database_host() -> str:
    try:
        return urlsplit(get_settings().database_url).hostname or "an unnamed host"
    except ValueError:
        return "an unparseable host"


async def _connect() -> None:
    try:
        await connect_db()
    except UnicodeDecodeError as exc:
        raise Refused(
            "could not connect: Prisma's config loader could not decode backend/pyproject.toml with "
            f"this interpreter's default codec ({exc.encoding}). Re-run with PYTHONUTF8=1."
        ) from exc


async def _actor(email: str) -> Any:
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
    env = os.environ if environ is None else environ
    stream = out or sys.stdout

    def say(line: str) -> None:
        stream.write(line + "\n")

    try:
        args = _parser(err or sys.stderr).parse_args(argv)
    except SystemExit as exc:
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
    for stream in (sys.stdout, sys.stderr):
        reconfigure = getattr(stream, "reconfigure", None)
        if reconfigure is not None:
            reconfigure(errors="replace")
    raise SystemExit(asyncio.run(run()))


if __name__ == "__main__":
    main()

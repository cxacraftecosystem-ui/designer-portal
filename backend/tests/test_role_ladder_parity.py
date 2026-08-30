"""The role ladder is written down once on the server and copied by hand into two other trees.

This is the file that holds the copies to the original.

``ROLE_RANK`` in ``app/core/deps.py`` is the server's answer to every permission question, and it is
mirrored BY HAND into two other source trees — TypeScript in ``frontend/``, Kotlin in ``android/`` —
and into README.md's role table, which is where most people meet the ladder for the first time.
Nothing compiles the four against each other. A tier added to one and forgotten in the others does
not break a build — it produces a web client that offers an action the API refuses, or a phone that
ranks the new tier at 0 (below a crowdsource volunteer, because every one of these lookups falls back
to zero) and hides every screen from the one group the feature was built for.

THIS IS NOT HYPOTHETICAL AND IT IS NOT OLD. ``DESIGNER`` (rank 35) is the tier this entire product is
built around, and for as long as it existed:

  * the sentence "six-tier ladder" was wrong in ``deps.py``'s OWN header while the seventh tier sat
    four lines below the sentence, and wrong the same way in ``frontend/lib/permissions.ts``, in
    README.md's role table and in ``docs/PERMISSIONS.md`` — which records having been corrected for
    exactly this once already. It is still wrong wherever only prose watches; the header of
    ``frontend/lib/permissions.ts`` names those sites, and nothing mechanical counts prose;
  * ``components/hero/AccessLadder.tsx`` — the public landing page's rendered ladder — was a six-row
    literal with no Designer row, under a header claiming it carried "the exact labels of
    ROLE_LABELS";
  * ``ALL_ROLES`` in ``tests/test_permission_matrix.py`` did not contain it, so every ladder-wide test
    in that file skipped the tier, and nothing went red when it was finally added (the gates had been
    right all along — but "already right" and "asserted" are different states, which is the sentence
    this whole file exists to act on).

INSPECTOR LANDED ON 2026-08-27, at rank 37 in that 36-39 gap, and this file did its job — which is
now a matter of record rather than of hope. The rollout touched twenty-three registered mirrors; the
rows below went red one at a time as each was reached, and the ONE that could not be edited in that
change (``MainActivity.kt``, held by another workstream) stayed red and named itself instead of
being discovered by an audit a quarter later. That is the entire value proposition of this file, so
it is worth stating plainly: a red row here is not a broken test, it is an unfinished rollout.

The 36-39 gap is now 36 and 38-39 — see the rank comment in ``deps.py`` for why 37 was chosen out of
the middle rather than the edge.

WHAT IT DOES. It reads the other trees AS TEXT — no browser, no JVM, no npm, no database — pulls each
ladder literal out of its source, and holds it to ``ROLE_RANK``. Reading source is a blunt
instrument and deliberately so: the drift being defended against is not a logic bug, it is somebody
typing six entries where seven belong, and text is where that happens. The same argument, in the same
words, produced ``tests/test_report_parity.py``, which reads the Kotlin report writer for the same
reason. WHEN ONE OF THESE FAILS, DO NOT EDIT THE EXPECTATION — the expectation is ``deps.py``. Find
which mirror lagged and finish the rollout there.

────────────────────────────────────────────────────────────────────────────────────────────────────
WHICH MIRRORS DEFEND THEMSELVES, AND WHICH DO NOT
────────────────────────────────────────────────────────────────────────────────────────────────────

Worth reading before adding a check here, because several of these rows are already mechanical and
a redundant check is a maintenance cost with no new failure behind it. Every row's own
``enforced_by`` field records this, and ``test_the_self_enforcing_claim_is_true_of_the_source`` below
re-derives it from the source so this list cannot become a comment that used to be true.

SELF-ENFORCING (``tsc`` fails on an incomplete one; the compiler already covers COMPLETENESS):
  * anything typed ``Record<UserRole, …>`` — ``ROLE_RANK`` and ``ROLE_LABELS`` in
    ``frontend/lib/permissions.ts``, ``TIER_COPY`` in ``components/hero/AccessLadder.tsx``, ``OFFERED``
    in ``e2e/dashboard-tile-parity-unit.spec.ts``. A missing key is a type error.

  * BUT ONLY AGAINST ``frontend/lib/types.ts``, NEVER AGAINST THE SERVER. ``UserRole`` is itself a
    hand-typed union, so the whole self-enforcing family is a closed loop that stays perfectly
    consistent while being perfectly wrong. ``types.ts`` is the row in this file that matters most,
    and it is the row nothing else in the repository checks at all.

ALREADY DIFFED AGAINST ``deps.py`` BY SOMETHING ELSE (this file overlaps them on purpose — both of
those run in the FRONTEND's gates, and a backend change is not going to run either):
  * ``frontend/lib/permissions.ts::ROLE_RANK`` — ``docs/tools/check-docs.mjs::checkRoleParity``
    parses both files and diffs keys and numbers in both directions.
  * ``frontend/lib/permissions.ts::ROLE_LABELS`` and its key ORDER —
    ``frontend/e2e/role-ladder-parity-unit.spec.ts``, which reads ``deps.py`` off disk.

NOT ENFORCED BY ANYTHING BUT THIS FILE — which is every Kotlin mirror, ``types.ts``, and every
hand-kept role tuple in a test:
  * Kotlin has no exhaustiveness over a ``mapOf``/``listOf`` of strings, so every Android copy
    compiles fine while short a tier: ``MainActivity.kt`` (ranks and labels), ``ui/AppNavigation.kt``
    (``FieldPermissions.RANKS`` and ``LABELS``), ``ui/TaskAdminScreen.kt`` (a display order and
    labels), ``ui/AccessRosterScreen.kt`` (a deliberately partial grant list), and the ``everyRole``
    tuple in each of the unit tests that iterate the ladder.
  * ``UserRole[]`` is an ARRAY, not a record: a short array type-checks. So every ``const ROLES:
    UserRole[]`` tuple in the e2e specs is hand-kept despite looking typed, and that is precisely the
    trap — ``OFFERED`` two declarations below one of them IS checked and the array is not.

────────────────────────────────────────────────────────────────────────────────────────────────────
THE TWO HALVES OF THIS FILE
────────────────────────────────────────────────────────────────────────────────────────────────────

1. ``MIRRORS`` — a registry, one row per literal, each held to ``ROLE_RANK``. Precise, and a registry
   goes stale the moment somebody adds another copy without reading this file.

2. ``test_no_unregistered_file_enumerates_the_ladder`` — the backstop for exactly that. It sweeps the
   two CLIENT trees for files that NAME five or more tiers and fails on any that this registry has
   never heard of. Five is a FIXED number and not ``len(ROLE_RANK)``: a threshold derived from the
   ladder's own size grows with it, so on the day INSPECTOR landed every existing seven-of-eight
   mirror would have dropped below the bar and the sweep would have gone quiet in the exact week it
   was needed. It did not, because five is five.

   It works. The Android row for ``DashboardTileParityTest.kt`` is in the registry because the sweep
   found that file an hour after the mirrors were enumerated by hand — another workstream had just
   written it, with its own ``everyRole`` tuple, and no amount of care over the registry would have
   caught it.

   The sweep stops at ``frontend/`` and ``android/``. Markdown is out of scope because prose names
   tiers constantly and a sweep over ``docs/`` would report a mirror for every third paragraph, so
   README.md's row is hand-registered with nothing behind it. That asymmetry is the honest one:
   source can be swept, prose cannot.

Skipped, not failed, when the client trees are absent — the backend is deployed without them.
"""

from __future__ import annotations

import re
from dataclasses import dataclass, field
from pathlib import Path

import pytest

from app.core.deps import ROLE_RANK

REPO = Path(__file__).resolve().parents[2]
FRONTEND = REPO / "frontend"
ANDROID = REPO / "android"

pytestmark = pytest.mark.skipif(
    not (FRONTEND / "lib").is_dir() or not (ANDROID / "app").is_dir(),
    reason="the frontend and Android source trees are not both present in this checkout",
)

#: Every tier the server knows. Derived, never typed — a literal here would be one more hand-kept
#: copy of the ladder, inside the file whose whole job is to stop hand-kept copies drifting.
TIERS = frozenset(ROLE_RANK)

_ANDROID_UI = "android/app/src/main/java/com/designprototype/workshop"
_ANDROID_TEST = "android/app/src/test/java/com/designprototype/workshop"


# ────────────────────────────────────────────────────────────────────────────────────────────────
# The registry
# ────────────────────────────────────────────────────────────────────────────────────────────────


@dataclass(frozen=True)
class Mirror:
    """One hand-kept copy of the ladder, and how to read it back out of its source.

    ``kind`` decides what is asserted, and the four are genuinely different obligations rather than
    strengths of the same one:

    ``ranked``   name-to-number. Held to ``ROLE_RANK`` by dict equality, so a wrong NUMBER fails as
                 loudly as a missing tier. This is the strongest form and the only one that can
                 catch a renumbering.
    ``closed``   a closed set of tier names with no numbers in it (a label table, a display order, a
                 union type). Held to ``ROLE_RANK``'s keys by set equality, both directions.
    ``covers``   the tiers are named across a whole FILE rather than gathered into one literal — an
                 inline ``for (const role of [...])`` inside a test body, say. Only coverage is
                 asserted: the file may legitimately name things that are not roles at all (
                 ``DwWorkshopCreationTest`` deliberately passes ``"SUPERUSER"`` to prove an unknown
                 role is refused), so the reverse direction would fail on correct code.
    ``partial``  fewer tiers than the ladder, ON PURPOSE, with the omissions written into ``absent``.
                 Asserted as: offered plus deliberately-absent equals the whole ladder. A new tier
                 therefore fails until somebody DECIDES which side it belongs on, which is the entire
                 point — the failure is a question, not a defect report.
    """

    path: str
    #: The declaration this row reads, named as the source names it. Appears verbatim in every
    #: failure message, because "which of the four maps in MainActivity.kt" is the first thing the
    #: person reading the failure needs to know.
    binding: str
    kind: str
    #: Captures the literal's body in group 1. ``None`` means "the whole file" and is only valid
    #: with ``kind="covers"``.
    pattern: str | None
    #: Why this copy exists and what breaks when it lags. Printed on failure — a parity failure that
    #: does not say what the drift COSTS gets fixed by deleting the assertion.
    why: str
    #: What ALREADY fails on drift here, or ``None`` for "nothing but this file". See the header.
    enforced_by: str | None = None
    #: ``partial`` only: the tiers this literal leaves out deliberately.
    absent: frozenset[str] = field(default_factory=frozenset)


MIRRORS: tuple[Mirror, ...] = (
    # ── frontend/lib ────────────────────────────────────────────────────────────────────────────
    Mirror(
        path="frontend/lib/types.ts",
        binding="UserRole",
        kind="closed",
        pattern=r"export type UserRole\s*=([\s\S]*?);",
        why=(
            "THE ROW THAT MATTERS MOST. Every `Record<UserRole, …>` in the web client is exhaustive "
            "against THIS union and nothing else, so a tier missing here silently un-enforces the "
            "four self-enforcing mirrors at once: `tsc` keeps passing, the records keep being "
            "complete, and all of them are complete against the wrong ladder. Nothing else in this "
            "repository compares it to the server."
        ),
    ),
    Mirror(
        path="frontend/lib/permissions.ts",
        binding="ROLE_RANK",
        kind="ranked",
        pattern=r"export const ROLE_RANK: Record<UserRole, number> = \{([\s\S]*?)\n\};",
        why=(
            "The client's answer to every permission question. A rank that disagrees with the server "
            "does not refuse anything — it offers a control the API then 403s, or hides one the user "
            "is entitled to."
        ),
        enforced_by="docs/tools/check-docs.mjs::checkRoleParity (keys and numbers, both directions)",
    ),
    Mirror(
        path="frontend/lib/permissions.ts",
        binding="ROLE_LABELS",
        kind="closed",
        pattern=r"export const ROLE_LABELS: Record<UserRole, string> = \{([\s\S]*?)\n\};",
        why=(
            "`roleLabel()` falls back to the raw enum, so a missing tier renders "
            "'CROWDSOURCE_VOLUNTEER' inside an English sentence on the screen where somebody decides "
            "who may sign a ministry report."
        ),
        enforced_by="frontend/e2e/role-ladder-parity-unit.spec.ts (labels, spelling and key order)",
    ),
    # ── frontend, rendered ──────────────────────────────────────────────────────────────────────
    Mirror(
        path="frontend/components/hero/AccessLadder.tsx",
        binding="TIER_COPY",
        kind="closed",
        pattern=r"const TIER_COPY: Record<UserRole, string> = \{([\s\S]*?)\n\};",
        why=(
            "The PUBLIC landing page's ladder — the one surface here that is rendered content rather "
            "than a comment, and the one that shipped for months with six rows and no Designer. It "
            "is a `Record` now precisely so that cannot recur."
        ),
        enforced_by="tsc, via Record<UserRole, string>",
    ),
    # ── frontend, hand-kept tuples inside tests ─────────────────────────────────────────────────
    #
    # These four are the audit finding this file was commissioned for: tuples that LOOK typed,
    # type-check while short, and quietly narrow the coverage of the suite they sit in. A test that
    # iterates six of seven tiers reports the same green as one that iterates seven.
    Mirror(
        path="frontend/e2e/dashboard-tile-parity-unit.spec.ts",
        binding="ROLES",
        kind="closed",
        pattern=r"const ROLES: UserRole\[\] = \[([\s\S]*?)\n\];",
        why=(
            "Drives the dashboard-tile parity sweep. A tier missing here is a tier whose dashboard is "
            "never compared with its nav — and the file's OWN `OFFERED` record two declarations below "
            "is exhaustive, which makes the gap invisible to a reader who saw the compiler catch the "
            "other one."
        ),
    ),
    Mirror(
        path="frontend/e2e/dashboard-tile-parity-unit.spec.ts",
        binding="OFFERED",
        kind="closed",
        pattern=r"const OFFERED: Record<UserRole, boolean> = \{([\s\S]*?)\n\};",
        why=(
            "The independent statement of who is offered the design-workshop tile, written role by "
            "role rather than derived from the predicate."
        ),
        enforced_by="tsc, via Record<UserRole, boolean>",
    ),
    Mirror(
        path="frontend/e2e/sketches-hub-guard-unit.spec.ts",
        binding="ROLES",
        kind="closed",
        pattern=r"const ROLES: UserRole\[\] = \[([\s\S]*?)\n\];",
        why=(
            "The route guard on /sketches-and-prototypes is a SET and not a rank threshold, so a new "
            "tier's answer cannot be inferred from the ladder — it has to be exercised, and this "
            "tuple is what exercises it."
        ),
    ),
    Mirror(
        path="frontend/e2e/design-workshop-create-gate-unit.spec.ts",
        binding="ALL_ROLES",
        kind="closed",
        pattern=r"const ALL_ROLES: UserRole\[\] = \[([\s\S]*?)\n\];",
        why=(
            "Pins who may START a design workshop — admins only, a designer deliberately not. A tier "
            "absent from the tuple is a tier whose create button nobody has asserted either way."
        ),
    ),
    Mirror(
        path="frontend/e2e/design-workshop-inspections-unit.spec.ts",
        binding="ROLES",
        kind="closed",
        pattern=r"const ROLES: UserRole\[\] = \[([\s\S]*?)\n\];",
        why=(
            "Pins the INSPECTOR tier's own gate on the web, and it is the row where a short tuple "
            "does the most damage in this file. `canInspectDesignWorkshops` is the one client "
            "predicate whose refusal is NOT monotonic in rank -- `assert_inspection_surface` 403s an "
            "ADMIN and a MASTER ADMIN by name -- and the spec proves that by asserting the predicate "
            "is false for every member of this tuple EXCEPT 'INSPECTOR'. A tier missing from the "
            "tuple is therefore a tier silently excused from the assertion, on the one surface where "
            "a wrongly-admitted account is offered a menu entry and an open URL that 403s.\n"
            "REGISTERED BECAUSE THE SWEEP BELOW FOUND IT, in the same way and for the same reason "
            "`DashboardTileParityTest.kt` was: the spec was written by the web lane of the Inspector "
            "wave hours before the handset lane ran this suite, and it named all eight tiers with no "
            "row here. Its Kotlin counterpart, `InspectionGateTest.kt`, is registered above."
        ),
    ),
    Mirror(
        path="frontend/e2e/designer-profile-unit.spec.ts",
        binding="ROLES",
        kind="closed",
        pattern=r"const ROLES: UserRole\[\] = \[([\s\S]*?)\n\];",
        why=(
            "Pins `canSeeDataTile`, the dashboard's View Data tile, which is a SET of four tiers and "
            "not a rank floor -- DESIGNER(35) and INSPECTOR(37) sit inside the Researcher-and-above "
            "range and are both refused. The spec proves that by mapping this tuple through the "
            "predicate and comparing the whole result to a per-tier table, so a tier missing from the "
            "tuple is a tier whose tile nobody has asserted either way, on the surface where the "
            "owner reported the original defect from the designer's side.\n"
            "THE THIRD FILE THIS SWEEP CAUGHT RATHER THAN THE REGISTRY ANTICIPATING -- the other two "
            "are `design-workshop-inspections-unit.spec.ts`, the row directly above this one, and "
            "`DashboardTileParityTest.kt`, further down among the Android rows; read their `why` for "
            "the same story told twice. It appeared in the tree while the handset lane was running "
            "this suite, which reported it and deliberately left it -- that lane had not read the "
            "file and would have been guessing at `kind` and `pattern`. Read before registering: the "
            "tuple is a complete, ordered ladder and `closed` is the honest kind.\n"
            "ONE ROW AND NOT TWO, although the file holds a second copy. `TILE_OFFERED` is a "
            "`Record<UserRole, boolean>`, so TypeScript already refuses it a missing tier and a "
            "second row here would assert what `tsc --noEmit` has enforced since it was written. The "
            "bare `UserRole[]` above has no such backstop, which is why it is the binding named."
        ),
    ),
    Mirror(
        path="frontend/e2e/design-workshop-designer-access-unit.spec.ts",
        binding="the inline role arrays in the test bodies",
        kind="covers",
        pattern=None,
        why=(
            "Proves that administering a workshop's viewers is admin-only for EVERY other tier. The "
            "roles are inline in the `for` loops rather than gathered into one binding, so this row "
            "can only assert that each tier is named somewhere in the file. That is the weak form; "
            "gathering the loops into one `ALL_ROLES` here would let this row be `closed` instead."
        ),
    ),
    # ── android: the five Kotlin copies ─────────────────────────────────────────────────────────
    #
    # An Android fix costs a tagged release and a fleet that may be offline for a fortnight, so these
    # are the rows where catching the omission BEFORE the tier ships is worth the most.
    Mirror(
        path=f"{_ANDROID_UI}/MainActivity.kt",
        binding="ROLE_RANK",
        kind="ranked",
        pattern=r"private val ROLE_RANK = mapOf\(([\s\S]*?)\n[ \t]*\)",
        why=(
            "The user-admin screen's role picker is BUILT from this map, so a missing entry makes the "
            "tier unassignable from the phone while the web offers it — two clients that disagree "
            "about who exists. It also ranks the missing tier 0, i.e. below a crowdsource volunteer."
        ),
    ),
    Mirror(
        path=f"{_ANDROID_UI}/MainActivity.kt",
        binding="ROLE_LABELS",
        kind="closed",
        pattern=r"private val ROLE_LABELS = mapOf\(([\s\S]*?)\n[ \t]*\)",
        why="`roleLabel` falls back to `role.orEmpty()`, so a missing tier renders as its raw token.",
    ),
    Mirror(
        path=f"{_ANDROID_UI}/ui/AppNavigation.kt",
        binding="FieldPermissions.RANKS",
        kind="ranked",
        pattern=r"private val RANKS = mapOf\(([\s\S]*?)\n[ \t]*\)",
        why=(
            "Every navigation gate on the phone reads this. `rank()` answers 0 for anything absent, "
            "and the drawer then hides every destination from the tier — the failure is a phone that "
            "looks signed in and has nothing in it."
        ),
    ),
    Mirror(
        path=f"{_ANDROID_UI}/ui/AppNavigation.kt",
        binding="FieldPermissions.LABELS",
        kind="closed",
        pattern=r"private val LABELS = mapOf\(([\s\S]*?)\n[ \t]*\)",
        why="`label()` falls back to the raw token, in the drawer, beside the user's own name.",
    ),
    Mirror(
        path=f"{_ANDROID_UI}/ui/TaskAdminScreen.kt",
        binding="ROLES_BY_RANK",
        kind="closed",
        pattern=r"private val ROLES_BY_RANK = listOf\(([\s\S]*?)\n[ \t]*\)",
        why=(
            "The whole vocabulary of the assignment builder's tier filter, and it filters BY "
            "MEMBERSHIP: a tier absent from this list has no chip, whatever the counts say. Nothing "
            "on screen reads as broken — the assignees are still in 'Everyone below me' — it simply "
            "cannot be filtered for."
        ),
    ),
    Mirror(
        path=f"{_ANDROID_UI}/ui/TaskAdminScreen.kt",
        binding="ROLE_LABELS",
        kind="closed",
        pattern=r"private val ROLE_LABELS = mapOf\(([\s\S]*?)\n[ \t]*\)",
        why=(
            "One of several hand-kept copies of the label table. It is shared with neither "
            "MainActivity.kt's nor AppNavigation.kt's — all three are typed out, in one language, in "
            "one module. (`frontend/lib/permissions.ts`'s header puts the total at four and misses "
            "AppNavigation.kt's `LABELS`, which is the same undercount in miniature.)"
        ),
    ),
    Mirror(
        path=f"{_ANDROID_UI}/ui/AccessRosterScreen.kt",
        binding="ACCESS_GRANTABLE_ROLES",
        kind="partial",
        pattern=r"private val ACCESS_GRANTABLE_ROLES = listOf\(([\s\S]*?)\n[ \t]*\)",
        # ADMIN and MASTER_ADMIN are excluded by a decision this screen's own header explains: an
        # admin COULD mint another admin here, but the place to do that is Manage users, where the
        # consequences of the ladder are on screen. Pinned rather than merely subset-checked so a
        # new tier cannot default into "not grantable" by nobody having thought about it.
        absent=frozenset({"ADMIN", "MASTER_ADMIN"}),
        why=(
            "The dropdown that lets a field researcher onto the platform at all. A tier absent from "
            "it cannot be admitted from the phone; a tier wrongly present is an accidental tap away "
            "from creating somebody who can lock the institution out."
        ),
    ),
    Mirror(
        path=f"{_ANDROID_UI}/ui/RosterFilters.kt",
        binding="ROSTER_ROLE_LADDER",
        kind="closed",
        pattern=r"val ROSTER_ROLE_LADDER: List<String> = listOf\(([\s\S]*?)\n\)",
        why=(
            "The role multi-select on BOTH roster screens — access and designer — is built from "
            "this one list, in the order it names. A tier absent from it is not merely unlabelled "
            "the way `ROLE_LABELS`' fallback would leave it: it is unfilterable, so an admin "
            "narrowing the roster to that tier gets rows for every tier EXCEPT the one that was "
            "just added — the exact shape of 'able to filter for rows carrying a tier they could "
            "not grant' the file's own header warns about, except silent: nothing errors, the list "
            "just quietly stops being a complete answer for the person most likely to be auditing "
            "it."
        ),
    ),
    # ── android: hand-kept tuples inside tests ──────────────────────────────────────────────────
    Mirror(
        path=f"{_ANDROID_TEST}/ui/FieldPermissionsTest.kt",
        binding="everyRole",
        kind="closed",
        pattern=r"private val everyRole = listOf\(([\s\S]*?)\n[ \t]*\)",
        why=(
            "The tuple every capability assertion in the phone's permission suite iterates. Short by "
            "one tier, the suite stays green and simply stops asking about that tier."
        ),
    ),
    Mirror(
        path=f"{_ANDROID_TEST}/ui/AccessRosterNavTest.kt",
        binding="everyRole",
        kind="closed",
        pattern=r"private val everyRole = listOf\(([\s\S]*?)\n[ \t]*\)",
        why=(
            "Asserts `canManageAccessRoster` equals exactly {ADMIN, MASTER_ADMIN} by FILTERING this "
            "tuple — so an unlisted tier can be silently admitted to the roster screen and the "
            "equality still holds."
        ),
    ),
    Mirror(
        path=f"{_ANDROID_TEST}/DesignWorkshopCardTest.kt",
        binding="everyRole",
        kind="closed",
        pattern=r"private val everyRole = listOf\(([\s\S]*?)\n[ \t]*\)",
        why="Same filter-and-compare shape, over who is offered the design-workshop card.",
    ),
    Mirror(
        path=f"{_ANDROID_TEST}/DashboardTileParityTest.kt",
        binding="everyRole",
        kind="closed",
        pattern=r"private val everyRole = listOf\(([\s\S]*?)\n[ \t]*\)",
        why=(
            "Asserts the Sketches & prototypes card is offered to exactly {DESIGNER, ADMIN, "
            "MASTER_ADMIN} by filtering this tuple, so an unlisted tier can be offered the card and "
            "the equality still holds.\n"
            "REGISTERED BECAUSE THE SWEEP BELOW FOUND IT, while this file was being written: the "
            "test did not exist when the mirrors were enumerated and appeared in the tree an hour "
            "later. That is the whole argument for having a backstop as well as a registry, and it "
            "is the reason to add a row here rather than reach for KNOWN_NON_MIRRORS."
        ),
    ),
    Mirror(
        path=f"{_ANDROID_TEST}/ui/designworkshop/InspectionGateTest.kt",
        binding="everyRole",
        kind="closed",
        pattern=r"private val everyRole = listOf\(([\s\S]*?)\n[ \t]*\)",
        why=(
            "The tuple that pins the INSPECTOR tier's own gate, and the row where a short tuple "
            "would do the most damage of any in this file. `canInspectDesignWorkshops` is set "
            "membership on {INSPECTOR} and is the ONE client predicate whose refusal is not "
            "monotonic in rank -- `assert_inspection_surface` 403s an ADMIN and a MASTER ADMIN by "
            "name. The test proves that by FILTERING this tuple and comparing the result to "
            "['INSPECTOR'], and by filtering it again for the rank floor a reader would reach for "
            "instead, so a tier missing here is a tier silently excused from both halves.\n"
            "It also asserts that the read door and the appointment door are DISJOINT by walking "
            "every tier, which is a claim that grows weaker with every tier the tuple lacks."
        ),
    ),
    Mirror(
        path=f"{_ANDROID_TEST}/ui/designworkshop/WorkshopViewerAdminGateTest.kt",
        binding="everyRole",
        kind="closed",
        pattern=r"private val everyRole = listOf\(([\s\S]*?)\n[ \t]*\)",
        why=(
            "Its own comment reads 'Every role the server's ROLE_RANK knows, so a new tier cannot be "
            "added without a decision.' That was a hope, not a mechanism, until this row. It is the "
            "clearest single example of the defect class: a correct claim, in a comment, with "
            "nothing holding it."
        ),
    ),
    Mirror(
        path=f"{_ANDROID_TEST}/data/DwWorkshopCreationTest.kt",
        binding="the role literals in `only admins and the master admin may create a workshop`",
        kind="covers",
        pattern=None,
        why=(
            "One `assertFalse` per tier, written out rather than looped, so there is no binding to "
            "read. Coverage only for the same reason: the file also passes 'SUPERUSER' on purpose, to "
            "prove an unknown role is never a creator."
        ),
    ),
    Mirror(
        path=f"{_ANDROID_TEST}/ui/WalkthroughSurfaceTest.kt",
        binding="the inline `listOf(...)` in `the menu row into the walkthrough is ungated for every role there is`",
        kind="covers",
        pattern=None,
        why=(
            "The walkthrough's nav entry is the one row this repository has decided must NEVER be "
            "gated — a crowdsource volunteer on day one needs it more than an admin does, per the "
            "test's own comment — so this walks every tier and asserts the menu item's `can` "
            "predicate admits each one. Coverage only: a tier absent from the literal is not a false "
            "claim about the ladder, it is a tier this test forgot to prove is still let in, which a "
            "future `can` predicate tightened elsewhere could then take away from silently."
        ),
    ),
    # ── the repository's front door ─────────────────────────────────────────────────────────────
    Mirror(
        path="README.md",
        binding="the Tier / Rank / Powers table",
        kind="ranked",
        pattern=r"\| Tier \| Rank \| Powers \|\n\|[-| ]+\|\n([\s\S]*?)\n\n",
        why=(
            "A rank map in a third language, and the one most people read FIRST — a contributor who "
            "never opens deps.py takes the ladder from this table and nowhere else. It carried the "
            "same off-by-one as everything else (six rows, no DESIGNER row, in a product whose "
            "primary user is a designer), and nothing has checked it since it was corrected.\n"
            "NOT IN THE SWEEP'S SCOPE, deliberately: the sweep walks the two client trees, because "
            "Markdown names tiers in narrative prose constantly and a sweep over docs/ would report a "
            "mirror for every paragraph that mentions three roles. So this row is registered by hand "
            "and there is no backstop behind it. `docs/PERMISSIONS.md` carries the same ladder as a "
            "Mermaid node (`MASTER_ADMIN · 60`) and is one more row away from being covered too — "
            "left for whoever owns that document, since its shape is different again."
        ),
    ),
)


# ────────────────────────────────────────────────────────────────────────────────────────────────
# Reading a literal back out of TypeScript or Kotlin
# ────────────────────────────────────────────────────────────────────────────────────────────────

#: A quoted string that is ENTIRELY an upper-snake token — `"DESIGNER"`, `'MASTER_ADMIN'`. Both
#: quotes are anchored so an English sentence containing an acronym cannot match.
_QUOTED_TIER = re.compile(r"""["']([A-Z][A-Z0-9_]{2,})["']""")

#: A bare object key in a TypeScript literal — `DESIGNER: "Designer",`. TS mirrors write the tier as
#: an identifier; Kotlin ones always quote it.
_BARE_KEY = re.compile(r"^\s*([A-Z][A-Z0-9_]{2,})\s*:", re.MULTILINE)

#: `NAME: 35` (TypeScript) or `"NAME" to 35` (Kotlin), where the value may be an identifier rather
#: than a literal — `AppNavigation.kt` writes `"DESIGNER" to RANK_DESIGNER`.
#:
#: ANCHORED TO THE START OF A LINE, for the reason [_QUOTED_TIER] states above and this pattern
#: learned the hard way. `_body()` hands back the literal's text WITH its comments, and the Kotlin
#: `to` infix is also an ordinary English word, so unanchored this matched the prose sentence
#: "Adding INSPECTOR to that set would be the wrong fix" sitting inside `MainActivity.kt`'s
#: `ROLE_RANK` and read it as the entry `INSPECTOR -> that`. `_ranked_tiers` then failed to resolve
#: `that` as an integer constant and BOTH ranked assertions went red naming MainActivity.kt — which
#: invites the reader to delete the comment, or the mirror row, to make a broken regex quiet.
#: A map entry always begins its own line; a comment line begins with `//` or a KDoc `*`, and a
#: Markdown row with `|` — none of which this pattern can start on. The value is held to the SAME
#: line (`[ \t]` rather than `\s`) so a sentence wrapping onto the next line cannot supply it
#: either. Verified against all four `kind="ranked"` mirrors: each still resolves to the full
#: 10/20/30/35/37/40/50/60 ladder, and `test_the_rank_reader_does_not_read_prose_as_a_rank_entry`
#: below pins both halves of that.
_RANK_ENTRY = re.compile(
    r"""^[ \t]*["']?([A-Z][A-Z0-9_]{2,})["']?[ \t]*(?::|\bto\b)[ \t]*([A-Za-z0-9_]+)""",
    re.MULTILINE,
)

#: A file-level integer constant, for resolving the identifiers above: `const val RANK_DESIGNER = 35`.
_INT_CONST = re.compile(r"\b(?:const\s+val|val|const)\s+([A-Za-z_]\w*)\s*(?::\s*\w+\s*)?=\s*(\d+)\b")

#: A Markdown table row — ``| `DESIGNER` | 35 | Run design & prototype workshops … |``. README.md's
#: role table is a rank map like any other, written in a third language, and a reader who never opens
#: `deps.py` takes the ladder from there and nowhere else.
_TABLE_ROW = re.compile(r"^\|\s*`?([A-Z][A-Z0-9_]{2,})`?\s*\|\s*(\d+)\s*\|", re.MULTILINE)


def _source(mirror: Mirror) -> str:
    path = REPO / mirror.path
    assert path.is_file(), (
        f"{mirror.path}: this file is registered as a role-ladder mirror and is no longer here. "
        f"It carried {mirror.binding}. If the mirror moved, re-point this row; if the copy is gone "
        f"for good, delete the row and say so in the commit — do not leave a row pointing at nothing."
    )
    return path.read_text(encoding="utf-8")


def _body(mirror: Mirror) -> str:
    """The literal's own text, or the whole file for a ``covers`` row."""
    source = _source(mirror)
    if mirror.pattern is None:
        return source
    match = re.search(mirror.pattern, source)
    assert match, (
        f"{mirror.path}: `{mirror.binding}` is no longer declared where this test reads it "
        f"(pattern: {mirror.pattern!r}).\n"
        f"Why the row exists: {mirror.why}\n"
        f"A renamed or reformatted literal is fine — re-point the pattern. What is NOT fine is "
        f"deleting this row to make the failure go away: that is how a mirror goes unwatched."
    )
    return match.group(1)


def _named_tiers(body: str) -> set[str]:
    return set(_QUOTED_TIER.findall(body)) | set(_BARE_KEY.findall(body))


def _ranked_tiers(mirror: Mirror, body: str) -> dict[str, int]:
    """``{tier: rank}``, resolving an identifier value through the file's own integer constants."""
    constants = {name: int(value) for name, value in _INT_CONST.findall(_source(mirror))}
    ranks: dict[str, int] = {}
    # Two disjoint shapes, unioned rather than chosen between: `NAME: 35` / `"NAME" to 35` for the
    # two programming languages, and `| `NAME` | 35 |` for the Markdown table. A row cannot match
    # both, so there is nothing for the caller to disambiguate.
    for tier, raw in [*_RANK_ENTRY.findall(body), *_TABLE_ROW.findall(body)]:
        if raw.isdigit():
            ranks[tier] = int(raw)
            continue
        assert raw in constants, (
            f"{mirror.path}: `{mirror.binding}` gives {tier} the rank `{raw}`, and this test could "
            f"not find `{raw}` declared as an integer constant in the same file. Either it is "
            f"computed (which makes the ladder unreadable to anything but a compiler — do not) or "
            f"this test's constant reader needs widening."
        )
        ranks[tier] = constants[raw]
    return ranks


def _id(mirror: Mirror) -> str:
    """The name pytest prints for a parametrised row: the file, then what in it. A `covers` row has
    no binding to name — its tiers are scattered through the file — so it says so."""
    leaf = mirror.path.rsplit("/", 1)[-1]
    return f"{leaf}::{'whole-file' if mirror.pattern is None else mirror.binding}"


# ────────────────────────────────────────────────────────────────────────────────────────────────
# The parity assertions
# ────────────────────────────────────────────────────────────────────────────────────────────────


@pytest.mark.parametrize("mirror", MIRRORS, ids=[_id(m) for m in MIRRORS])
def test_every_mirror_carries_exactly_the_tiers_the_server_has(mirror: Mirror) -> None:
    """Every tier in ``ROLE_RANK`` is in the mirror, and the mirror invents none.

    BOTH DIRECTIONS, and the second is the one a person writing this by hand would skip. A tier the
    server has and a mirror lacks is a client that cannot see an account type. A tier a MIRROR has and
    the server does not is worse: it is an option in a picker that produces a 403 on submit, or — on
    the phone — a role that can be assigned and then cannot sign in.
    """
    body = _body(mirror)
    named = _named_tiers(body) if mirror.kind != "ranked" else set(_ranked_tiers(mirror, body))

    if mirror.kind == "partial":
        # Handled first and separately, because the message is the point. For a deliberately partial
        # list the interesting failure is not "a tier is missing" — some are missing on purpose — it
        # is "a tier is neither offered nor deliberately excluded", and that is a question for a
        # person, not a defect report. Falling through to the generic message below would tell them
        # to go and add the tier, which is exactly the decision they are supposed to be making.
        offered = named & TIERS
        overlap = sorted(offered & mirror.absent)
        assert not overlap, (
            f"{mirror.path}: `{mirror.binding}` now offers {', '.join(overlap)}, which this test "
            f"records as deliberately excluded. If the decision changed, change `absent` here in the "
            f"same commit and say why — the exclusion is a judgement about blast radius, not an "
            f"oversight."
        )
        undecided = sorted(TIERS - offered - mirror.absent)
        assert not undecided, (
            f"{mirror.path}: `{mirror.binding}` neither offers {', '.join(undecided)} nor records it "
            f"as deliberately absent.\n"
            f"This failure is a QUESTION, not a defect: decide whether that tier may be granted here, "
            f"then either add it to the list or add it to `absent` in this file with the reason.\n"
            f"What the list is for: {mirror.why}"
        )
        return

    missing = sorted(TIERS - named)
    assert not missing, (
        f"{mirror.path}: `{mirror.binding}` does not carry {', '.join(missing)}, which "
        f"`backend/app/core/deps.py::ROLE_RANK` does.\n"
        f"What that costs: {mirror.why}\n"
        f"Fix the mirror, not this test — deps.py is the ladder."
    )

    if mirror.kind == "covers":
        # Deliberately no reverse check; see Mirror's docstring for why the whole-file scope cannot
        # have one.
        return

    invented = sorted(named - TIERS)
    assert not invented, (
        f"{mirror.path}: `{mirror.binding}` carries {', '.join(invented)}, which the server's "
        f"`ROLE_RANK` does not have. Either the tier was renamed and this mirror kept the old "
        f"spelling, or it was removed from deps.py and left here — both offer the user an account "
        f"type the API will refuse."
    )


@pytest.mark.parametrize(
    "mirror",
    [m for m in MIRRORS if m.kind == "ranked"],
    ids=[_id(m) for m in MIRRORS if m.kind == "ranked"],
)
def test_every_ranked_mirror_uses_the_server_s_numbers(mirror: Mirror) -> None:
    """The NUMBERS, not just the names — the half a set comparison cannot see.

    DESIGNER is 35 rather than 40 because it was inserted into the gap the original tens deliberately
    left, so that no stored role and no existing comparison changed meaning. A mirror that "adds the
    tier" by renumbering instead — pushing PROFESSOR to 45, say — has every name the server has and is
    still wrong everywhere, and the first symptom is a professor who cannot open a screen they opened
    yesterday. INSPECTOR landed in the same kind of gap at 37 on 2026-08-27, which is what this
    assertion was written for — and it caught ``MainActivity.kt``'s ``ROLE_RANK`` on the way through,
    where a name-only comparison would have reported the same map as merely "missing a tier".
    """
    ranks = _ranked_tiers(mirror, _body(mirror))
    wrong = {
        tier: (rank, ROLE_RANK[tier])
        for tier, rank in ranks.items()
        if tier in ROLE_RANK and rank != ROLE_RANK[tier]
    }
    assert not wrong, (
        f"{mirror.path}: `{mirror.binding}` disagrees with `backend/app/core/deps.py::ROLE_RANK` on "
        + "; ".join(f"{tier} (mirror {mine}, server {theirs})" for tier, (mine, theirs) in sorted(wrong.items()))
        + f".\nWhat that costs: {mirror.why}"
    )
    # And the whole dict, so a tier present in one and absent in the other cannot slip past the
    # comparison above (which only looks at keys the mirror already has).
    assert ranks == dict(ROLE_RANK), f"{mirror.path}: `{mirror.binding}` is not deps.py's ladder."


def test_the_self_enforcing_claim_is_true_of_the_source() -> None:
    """`enforced_by="tsc, …"` is a claim about another file, so re-derive it rather than trust it.

    The header of this file tells a future reader that four mirrors are already exhaustive by
    compilation and that a fifth check here would be redundant. That is advice worth acting on, which
    makes it advice worth being wrong about: if somebody loosens `Record<UserRole, string>` to
    `Partial<Record<…>>` or to a plain object, the compiler stops enforcing anything and the note here
    still says it does. This is the one assertion that keeps a comment honest.
    """
    for mirror in MIRRORS:
        if not (mirror.enforced_by or "").startswith("tsc"):
            continue
        declaration = re.search(
            rf"(?:const|let)\s+{re.escape(mirror.binding)}\s*:\s*Record<UserRole,\s*\w+>",
            _source(mirror),
        )
        assert declaration, (
            f"{mirror.path}: `{mirror.binding}` is recorded here as self-enforcing via "
            f"`Record<UserRole, …>`, and its declaration no longer has that type. Either restore the "
            f"type — `tsc` catching a missing tier at build time beats this suite catching it later — "
            f"or clear `enforced_by` on that row so the header stops promising a check that is gone."
        )


def test_every_mirror_row_is_well_formed() -> None:
    """The registry's own invariants, so a badly-written row cannot pass by asserting nothing."""
    for mirror in MIRRORS:
        assert mirror.kind in {"ranked", "closed", "covers", "partial"}, mirror
        assert (mirror.pattern is None) == (mirror.kind == "covers"), (
            f"{mirror.path}::{mirror.binding}: only a `covers` row may scan a whole file, and every "
            f"`covers` row must — a pattern-less row of any other kind would compare the file's every "
            f"upper-case token against the ladder and fail on unrelated constants."
        )
        assert (mirror.absent != frozenset()) == (mirror.kind == "partial"), (
            f"{mirror.path}::{mirror.binding}: `absent` is meaningful only for a `partial` row, and a "
            f"`partial` row with nothing absent is a `closed` one."
        )
        assert not (mirror.absent - TIERS), (
            f"{mirror.path}::{mirror.binding}: `absent` names {sorted(mirror.absent - TIERS)}, which "
            f"is not a tier the server has."
        )
        assert mirror.why.strip(), f"{mirror.path}::{mirror.binding}: every row states its cost."


def test_the_rank_reader_does_not_read_prose_as_a_rank_entry() -> None:
    """`_RANK_ENTRY` must not mistake an English sentence inside the literal for a map entry.

    `_body()` returns the literal's text WITH its comments — a deliberate choice, since a comment
    naming a tier belongs to the mirror — and Kotlin's `to` is an ordinary English word. Unanchored,
    the pattern read `Adding INSPECTOR to that set would be the wrong fix` (a real comment inside
    `MainActivity.kt`'s `ROLE_RANK`) as `INSPECTOR -> that`, and both ranked assertions failed
    naming the Kotlin file rather than this one. Pinned here because the next author's instinct on
    seeing that failure is to reword the Kotlin, which re-arms the trap for the sentence after it.
    """
    prose = (
        '        // grants them. Adding INSPECTOR to that set would be the wrong fix — the\n'
        '        // read-only workshop. See DESIGNER: the gap at 36-39 was left free.\n'
        '         * PROFESSOR to 40 stays where it is.\n'
        '        # RESEARCHER: 30 in the server\n'
    )
    assert _RANK_ENTRY.findall(prose) == [], (
        "an English sentence inside a rank map is being read as a rank entry; see the note on "
        "`_RANK_ENTRY` for why that failure points at the wrong file"
    )

    real = '        "DESIGNER" to RANK_DESIGNER,\n        INSPECTOR: 37,\n'
    assert _RANK_ENTRY.findall(real) == [("DESIGNER", "RANK_DESIGNER"), ("INSPECTOR", "37")], (
        "the anchoring went too far and stopped reading the two shapes a real mirror is written in"
    )


# ────────────────────────────────────────────────────────────────────────────────────────────────
# The backstop: nothing may enumerate the ladder without being registered above
# ────────────────────────────────────────────────────────────────────────────────────────────────

#: How many DISTINCT tiers a file must name before it is treated as a ladder enumeration.
#:
#: A FIXED NUMBER, NOT ``len(ROLE_RANK)``, and this is the single most important line in the sweep.
#: A threshold of "all of them" grows with the ladder: on 2026-08-27, when INSPECTOR was added, every
#: mirror that still listed the seven older tiers dropped from "all" to "seven of eight" — and with a
#: derived threshold the sweep would have gone silent in the exact week it was the only thing
#: watching. That is no longer a prediction; it is what this constant prevented. Five is low enough
#: that a genuinely partial enumeration is caught and high enough that a page mentioning a couple of
#: tiers in passing is not. DO NOT derive it, and do not raise it to quiet a failure.
LADDER_SHAPED = 5

#: Files that name five or more tiers and are NOT mirrors of the ladder. Each needs a reason, and the
#: reason has to be "this does not purport to enumerate the ladder" — never "this failure was
#: inconvenient". Anything that DOES enumerate belongs in ``MIRRORS``, not here.
KNOWN_NON_MIRRORS: dict[str, str] = {
    "frontend/e2e/identity-ocr-unit.spec.ts": (
        "Two SEPARATE partial loops asserting two different predicates — {RESEARCHER, PROFESSOR} may "
        "open the artisan form and are refused the card reader, {DESIGNER, ADMIN, MASTER_ADMIN} are "
        "in the set the endpoint admits. Neither loop claims to be the ladder, and the two bottom "
        "tiers are outside both on purpose. A new tier does want a line here, but the assertion is "
        "per-predicate, so requiring completeness would be requiring the wrong shape."
    ),
}

_SWEEP_SUFFIXES = {".ts", ".tsx", ".kt"}
#: Directories with no hand-written source in them. Build output is the one that matters: Gradle and
#: Next.js both emit generated files that quote role names, and a sweep that read them would report a
#: "mirror" nobody can fix. (The full copies of both trees under `.claude/worktrees/` are out of reach
#: already — the sweep starts at `frontend/` and `android/` and never sees them.)
_SWEEP_SKIP = {"node_modules", ".next", "build", "dist", ".gradle", ".idea"}


def _sweep_files() -> list[Path]:
    found: list[Path] = []
    for tree in (FRONTEND, ANDROID):
        for path in tree.rglob("*"):
            if path.suffix not in _SWEEP_SUFFIXES or not path.is_file():
                continue
            if _SWEEP_SKIP & set(path.relative_to(REPO).parts):
                continue
            found.append(path)
    return found


def test_no_unregistered_file_enumerates_the_ladder() -> None:
    """A new copy of the ladder must not be able to appear unwatched.

    ``MIRRORS`` is a hand-kept registry, which makes it the same kind of artefact as the tuples it
    polices: correct on the day it was written, and silent afterwards. This is the assertion that
    makes it self-renewing. Anything in either client tree that names five or more tiers is either a
    row above or an entry in ``KNOWN_NON_MIRRORS`` with a reason — there is no third state.

    IT WILL FIRE ON HONEST WORK, and that is the design. Adding a screen that enumerates the tiers
    should cost one line here, chosen deliberately, rather than costing nothing and being discovered
    by an audit two quarters later.
    """
    registered = {m.path for m in MIRRORS} | set(KNOWN_NON_MIRRORS)
    stray: dict[str, list[str]] = {}
    for path in _sweep_files():
        relative = path.relative_to(REPO).as_posix()
        if relative in registered:
            continue
        named = sorted(_named_tiers(path.read_text(encoding="utf-8")) & TIERS)
        if len(named) >= LADDER_SHAPED:
            stray[relative] = named

    assert not stray, (
        "These files name "
        f"{LADDER_SHAPED} or more role tiers and are not registered in this file:\n"
        + "\n".join(f"  {p}  ({', '.join(t)})" for p, t in sorted(stray.items()))
        + "\n\nIf the file mirrors the ladder — a rank map, a label table, a display order, a tuple a "
        "test iterates — add a `Mirror(...)` row so a new tier cannot skip it. If it merely asserts "
        "something about several tiers without claiming to enumerate them, add it to "
        "`KNOWN_NON_MIRRORS` WITH THE REASON. Do not raise `LADDER_SHAPED` to silence this: the "
        "threshold is fixed so that the sweep keeps working when the ladder grows."
    )


def test_the_sweep_actually_reaches_both_client_trees() -> None:
    """A sweep that silently walks nothing passes forever.

    ``rglob`` over a path that does not exist yields nothing and raises nothing, so the assertion
    above would go green on a checkout where the client trees had been moved or renamed — the exact
    shape of green-that-means-nothing this repository's test discipline exists to refuse. Pinning
    that each tree contributes files, and that the sweep really does find the registered mirrors in
    both languages, is what stops that.
    """
    swept = {p.relative_to(REPO).as_posix() for p in _sweep_files()}
    assert any(p.startswith("frontend/") for p in swept), "the sweep walked no frontend source"
    assert any(p.startswith("android/") for p in swept), "the sweep walked no Android source"
    for mirror in MIRRORS:
        # Only the two client trees. A mirror registered outside them — README.md's role table — is
        # hand-kept with no backstop behind it, which its own `why` says out loud; asserting the sweep
        # reached it would be asserting something this test deliberately does not do.
        if not mirror.path.startswith(("frontend/", "android/")):
            continue
        assert mirror.path in swept, (
            f"{mirror.path} is registered as a mirror and the sweep does not reach it, so the "
            f"backstop could not have caught it had it been unregistered. Check `_SWEEP_SKIP` and "
            f"`_SWEEP_SUFFIXES`."
        )

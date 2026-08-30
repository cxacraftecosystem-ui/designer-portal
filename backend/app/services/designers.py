"""The designer roster and the designer profile: the two facts kept deliberately apart.

**THE ROSTER GATES SIGN-IN. THE ROLE GRANTS POWERS.** ``User.role`` says what somebody may DO;
``DesignerRoster`` says whether the institution still recognises them at all. The temptation is
always to collapse the two and revoke access by demoting the account, and that is precisely what
must not happen here: the role column is read when deciding who may review whose work and whose
records may be edited, so a demotion is RETROACTIVE — it silently rewrites the standing of every
workshop the designer ever ran. Suspending a roster row ends their sessions and leaves the
authorship of two years of fieldwork exactly as it was.

**THE PROFILE IS COPIED, NEVER REFERENCED.** :func:`prefill_from_profile` returns values that a
new workshop's stage entries are seeded WITH; nothing downstream ever reads the profile again.
See that function's docstring for the failure that rule prevents.

Every email that reaches this module is lower-cased on the way in. ``DesignerRoster.email`` is
unique and is joined to ``User.email``, and a roster row typed ``A.Sharma@Example.org`` that
never matches the account signing in as ``a.sharma@example.org`` is a designer locked out of the
app by a capital letter, with an admin looking straight at the row that was supposed to let them
in. :func:`normalise_email` is the single place that happens.

**AND A CAPITAL LETTER WAS NEVER THE ONLY SPELLING THAT DID THAT.** Google is the only sign-in path
a designer has, and Google treats the dots and the ``+tag`` in a Gmail local part as decoration:
``sandy.craft3@gmail.com``, ``sandycraft3@gmail.com`` and ``sandycraft3+work@googlemail.com`` are
ONE mailbox to it and were three unrelated keys here. So an admin could type the address off a
business card, watch the roster screen show exactly the person they meant, and have that person
read *"Your designer access has been suspended"* — refused by a row sitting in front of the admin
saying they are empanelled, with nothing on either screen able to explain the difference.
:func:`canonical_email` is that second, coarser key, and :func:`email_match_keys` is how a gate asks
about both spellings in one indexed query. ``normalise_email`` is deliberately NOT changed to do
this: lower-casing is lossless and safe for every address on earth, while throwing dots away is
neither, and outside the two Gmail domains it would merge two different people.
"""

from datetime import UTC, datetime
from typing import Any

from fastapi.encoders import jsonable_encoder
from prisma.errors import UniqueViolationError

from app.core.db import db

# Every column of ``DesignerProfile`` a person may write. Named once, here, and consumed by the
# serializer, the updater and the schema's field list alike — three copies of a twenty-two-name
# list is two copies that will disagree, and the way that failure surfaces is a field the designer
# can save and then cannot see.
#
# WHAT IS DELIBERATELY ABSENT IS ``locationId``, and it is absent for the same reason ``id`` and
# ``userId`` are: this is the list of columns a PERSON writes BY NAME, and a foreign key to a row
# the server just created is not one of them. The body carries ``location`` — an object — and
# ``update_profile`` merges the resulting id outside this loop, exactly as all six field-record
# routes do. Adding the id here would also fail two guard tests on the spot
# (``tests/test_designer_prefill_contract.py``): every name below must exist on
# ``DesignerProfileUpdate``, and every name below must reach a report field.
PROFILE_FIELDS: tuple[str, ...] = (
    "displayName",
    "localName",
    "designation",
    "institution",
    "department",
    "qualification",
    "specialisation",
    "experienceYears",
    # THE SECOND HALF OF THE SAME ANSWER, ADDED 2026-08-29 — A SECOND COLUMN AND NEVER A TOTAL.
    # The form asks for "5 years" beside "6 months" and the read-back has to hand back exactly what
    # was chosen, which a single stored 66 cannot do: it cannot tell "5 years and 6 months" from
    # "66 months", and it would make ``experienceYears`` beside it a derived column overnight. The
    # argument is written out in full on ``DesignerProfile.experienceMonths`` in schema.prisma.
    #
    # NAMED ONCE, HERE, WHICH IS THIS TUPLE'S WHOLE PURPOSE. The serializer below, the updater below
    # and ``test_the_wire_body_accepts_every_column_the_prefill_reads`` all read the column off this
    # line, so it cannot end up writable and unreadable — the failure mode the comment above names.
    #
    # NULL IS NOT ZERO ON THIS COLUMN, AND THE WHOLE PATH KEEPS THEM APART. Every profile stored
    # before it existed answered a form that asked for years alone; the route dumps the body with
    # ``exclude_unset=True`` and ``update_profile`` writes only the keys that survived, so those rows
    # keep NULL and the second dropdown opens BLANK rather than pre-answered with a 0 nobody picked.
    "experienceMonths",
    "biography",
    "phone",
    "email",
    "website",
    "addressLine",
    "city",
    "state",
    "pincode",
    "photoMediaId",
    "signatureMediaId",
    # THE CV, ADDED 2026-08-25. A media id like the two above it and never a URL — see
    # `frontend/lib/designers.ts` rule 4 for why a stored pre-signed link is a report that prints a
    # broken figure three months later with nothing on the row to say why. The Designer Page renders
    # it inline where it is a PDF.
    #
    # `designerCv` ON STAGE 3 IS THE COPY A REPORT *NAMES*, NOT ONE IT CARRIES, and this line said
    # "carries" until it was measured. A FILE field reaches the document as its label plus a count —
    # "1 document attached" — because no annexure in this product admits a FILE, and `build_report`
    # emits a warning saying the bytes are not inside the file. The distinction matters here of all
    # places: this is the table that decides what crosses into a workshop, so a reader deciding
    # whether to add another FILE column needs to know what crossing actually buys.
    "cvMediaId",
    "empanelmentNo",
    "empanelmentDate",
)

# Columns of DesignerProfile that are DateTime in Postgres and ISO strings on the wire. A raw
# string reaching a DateTime column is a driver-level error and therefore a bare 500, not a 422,
# so every one of these goes through :func:`_parse_date` before the write.
PROFILE_DATE_FIELDS: frozenset[str] = frozenset({"empanelmentDate"})

#: The relations :func:`profile_payload` reads, and therefore the ones EVERY query that produces a
#: profile has to load. Passed by both upserts below rather than written out at each call site.
#:
#: PRISMA ANSWERS ``None`` FOR AN UNREQUESTED RELATION AND DOES NOT RAISE, which is the entire reason
#: this is a named constant instead of an argument somebody remembers. The generated client declares
#: the attribute as ``location: Optional['models.Location'] = None``, so a serializer reading
#: ``row.location`` off a row fetched without this include does not fail — it serialises ``null``,
#: for every designer, for ever, with nothing on any screen to say the address was never asked for.
#: There are exactly two paths that produce a profile (the GET's ``get_or_create_profile`` and the
#: PUT's ``update_profile``), and a profile whose address is there on read and gone on save is worse
#: than one that never had an address at all.
PROFILE_INCLUDE: dict[str, Any] = {"location": True}


def normalise_email(email: Any) -> str:
    """The one canonical form of an email in the roster. See the module docstring."""
    return str(email or "").strip().lower()


#: THE TWO DOMAINS GOOGLE SERVES ONE MAILBOX FROM. ``googlemail.com`` is the name Gmail was sold
#: under in Germany, Russia and the United Kingdom for years; Google still accepts it and still
#: delivers it to the ``gmail.com`` inbox of the same name. It is not a second provider and it is
#: not a second mailbox.
#:
#: **A CLOSED LIST, SHORT ON PURPOSE, AND THE ONLY THING THAT MAKES THE FOLD BELOW SAFE.**
#: Everything :func:`canonical_email` does to a local part — deleting its dots, cutting it at a
#: ``+`` — is destructive and irreversible, and it is defensible only because the provider itself
#: publishes that those characters carry no meaning for it. Nobody else publishes that. A Postfix,
#: Exchange or university domain may perfectly well deliver ``a.sharma@`` and ``asharma@`` to two
#: different members of staff, and folding those together here would not be a near-miss: it would
#: silently hand one colleague's sign-in, empanelment and workshop authorship to another, in a table
#: whose whole job is deciding who is who. Add a domain to this set only on that domain's own
#: statement that the two spellings are one mailbox — never on the observation that it probably is.
GMAIL_DOMAINS: frozenset[str] = frozenset({"gmail.com", "googlemail.com"})


def canonical_email(email: Any) -> str:
    """The MAILBOX an address reaches, for the one provider that has told us the spelling is noise.

    A second key beside :func:`normalise_email`, never a replacement for it. For every address that
    is not on a :data:`GMAIL_DOMAINS` domain this returns exactly what ``normalise_email`` returns,
    byte for byte — so the storage form of every other address in this product is unchanged by this
    function existing, which is the property that made it safe to start writing rows through it.

    On a Gmail address it does three things, in this order:

    1. **Cuts the local part at the first ``+``.** ``sandycraft3+ministry@gmail.com`` is a filing
       label the person invented; the mailbox is ``sandycraft3``.
    2. **Deletes every dot from what is left.** This is the one that caused the reported outage.
    3. **Folds the domain to ``gmail.com``**, so the ``googlemail.com`` spelling of an address and
       the ``gmail.com`` spelling of it are one key rather than two.

    **WHY THE PLUS IS NOT STRIPPED FOR EVERYBODY, WHICH LOOKS LIKE AN OVERSIGHT AND IS NOT.**
    Sub-addressing is a convention, not a rule: the separator is configurable per site (it is
    ``recipient_delimiter`` in Postfix and is frequently ``-`` instead, or off entirely), and ``+``
    is an ordinary legal character in an RFC 5322 local part. A domain that never turned
    sub-addressing on may have a real, distinct mailbox called ``accounts+billing@`` — and the cost
    of guessing wrong is the same as it is for dots, so it is refused for the same reason.

    **``partition`` AND NOT ``rpartition``, WHICH DECIDES WHAT HAPPENS TO A MALFORMED ADDRESS.**
    Splitting on the FIRST ``@`` means a string with two of them (``a@b@gmail.com``) has a "domain"
    of ``b@gmail.com``, which is not in the set, so it is returned untouched. Splitting on the last
    would instead canonicalise it into ``ab@gmail.com`` — a key that can collide with a real
    person's mailbox. Nothing but a single-``@`` Gmail address is ever transformed here, and that is
    the whole point: the destructive path has to be the narrow one.

    **AN EMPTY LOCAL PART IS REFUSED RATHER THAN RETURNED.** ``...@gmail.com`` and ``+x@gmail.com``
    both reduce to a bare ``@gmail.com``, and answering that would make two different unusable
    strings share one key — the exact merge this function's domain restriction exists to prevent,
    arrived at from the other end. Neither is a mailbox, so the normalised form is handed back
    unchanged and the caller's ``IN`` list simply holds one dead key that matches nothing.
    """
    address = normalise_email(email)
    local, at, domain = address.partition("@")
    if not at or domain not in GMAIL_DOMAINS:
        return address
    mailbox = local.split("+", 1)[0].replace(".", "")
    if not mailbox:
        return address
    return f"{mailbox}@gmail.com"


def email_match_keys(email: Any) -> list[str]:
    """Every stored spelling of one mailbox, as the ``IN`` list of ONE indexed query.

    Returns ``[]`` for an unusable address, one key where there is nothing to canonicalise, and two
    — **the literal first, then the canonical** — where the address is a Gmail alias. Both roster
    tables have a UNIQUE index on ``email``, so a two-key ``IN`` can match at most two rows, and
    that bound is what lets every caller below resolve a collision by hand instead of paginating.

    **THE LITERAL FORM IS IN THE LIST BECAUSE OF THE ROWS THAT ARE ALREADY THERE.** Every roster row
    written before this change is stored under whatever an admin typed, dots and all. A gate that
    canonicalised only the *incoming* address would look up ``sandycraft3@gmail.com`` and no longer
    find the ``sandy.craft3@gmail.com`` row that admits that person today — turning a fix for a
    lock-out into a fresh lock-out for everybody the old spelling was working for. Keeping the
    literal is not belt-and-braces; it is the entire backwards-compatibility story, and deleting it
    as redundant would break sign-in for exactly the people this feature is for.

    **ONE QUERY, NEVER TWO, AND NEVER A SCAN.** Two round trips would be two answers that can
    disagree — a row suspended between them decides the gate differently depending on which read
    won — and this runs on the sign-in path of a deployment whose ``DATABASE_CONNECTION_LIMIT`` is
    10, so doubling the queries per login is a real cost paid on the busiest path there is. The
    other tempting shape, reading the table and canonicalising in Python, is worse than slow: it is
    a gate whose cost grows with the roster and which has to be given a read cap, and a capped read
    in an admission decision is a person who cannot sign in because they sorted late.
    """
    literal = normalise_email(email)
    if not literal:
        return []
    canonical = canonical_email(literal)
    if canonical == literal:
        return [literal]
    return [literal, canonical]


# --------------------------------------------------------------------------------------
# The roster
# --------------------------------------------------------------------------------------


async def roster_allows(email: Any) -> bool:
    """Is this MAILBOX empanelled — and is every row that spells it still active?

    The single question the sign-in gate asks. "A row exists" and "the row still admits them" are
    two different facts and a reader who conflates them has written an authentication bypass: a
    suspended designer's row is still there, which is the entire point of suspending rather than
    deleting it. That has not changed. What changed is where the second fact is established.

    **``isActive`` MOVED OUT OF THE WHERE CLAUSE, AND IT MADE THE GATE STRICTER RATHER THAN
    LOOSER.** Asking for ``{"email": {"in": keys}, "isActive": True}`` would answer this question by
    IGNORING any suspended row it found — so a designer whose empanelment an admin revoked under
    ``sandy.craft3@gmail.com`` would be admitted by an active ``sandycraft3@gmail.com`` row created
    later by some other path, and the revocation would be routed around by a spelling. Reading the
    rows and requiring ALL of them to be active is the fail-closed reading of the same question:
    where two spellings of one mailbox disagree about whether somebody may sign in, the answer is
    no, and an administrator settles it on the screen where the two rows are visible.

    For every address that has exactly one roster row — which is every address in the table until
    two spellings of one Gmail mailbox get written — this is byte-for-byte the old behaviour: one
    active row admits, one suspended row refuses, no row refuses.
    """
    keys = email_match_keys(email)
    if not keys:
        return False
    rows = await db.designerroster.find_many(where={"email": {"in": keys}})
    # ``all`` over an empty list is True, so the emptiness test is not a tidiness guard: without it
    # an address with no roster row at all would be ADMITTED, which is the one answer this function
    # must never give.
    return bool(rows) and all(row.isActive for row in rows)


#: THE SENTENCE A DERIVED EMPANELMENT WRITES ABOUT ITSELF, so that an audit can tell one apart from
#: an administrator's.
#:
#: ``addedById`` alone cannot answer that question. It is NULL on a row this function derived from
#: somebody's sign-in, and it is ALSO NULL on a row an admin created years ago whose account has
#: since been deleted — the relation is ``onDelete: SetNull``, so the id quietly becomes NULL
#: without the row changing in any other way. An admin looking at ``/admin/designers`` and trying to
#: work out why somebody is empanelled would read those two rows as the same thing. This sentence is
#: the part that stays true, and it is what the roster screen actually shows a human.
DERIVED_EMPANELMENT_NOTE = (
    "Empanelled automatically because this address is admitted on the platform allow-list as a "
    "designer. No administrator added this row on the designer roster directly."
)


async def ensure_empanelled(
    email: Any, *, actor_id: str | None = None, note: str | None = None
) -> bool:
    """Empanel an allow-listed designer — and ONLY where they have no roster row at all.

    Returns True only when this call actually created a row, so a caller that wants to log or report
    the empanelment can tell "I made this" from "it was already there".

    **WHY THIS EXISTS.** The two rosters are independent gates and passing one has never passed the
    other: ``AccessRoster`` decides who may sign in and can promote the account to DESIGNER through
    ``admitRole``, while ``DesignerRoster`` decides whether that DESIGNER is still empanelled. So an
    administrator could admit somebody as a designer, watch the allow-list screen show them ACTIVE,
    and have the person read *"Your designer access has been suspended"* at the sign-in page —
    referring to an empanelment that was never granted, on a screen showing no row at all to explain
    it. That is not hypothetical: it is what ``sandycraft3@gmail.com`` hit, and the only remedy was
    for an admin to notice and empanel the same person a second time in a second screen. Admitting
    somebody as a designer now grants the empanelment, which is what an admin doing it already
    believed they were doing.

    **THE ONE RULE THAT MUST NOT BE GOT WRONG: CREATE ONLY WHERE NO ROW EXISTS.** This never flips
    ``isActive`` back to True on a suspended row, and it never touches an existing row in any other
    way either. Suspension is a deliberate revocation — the roster suspends rather than deletes
    precisely so the record of the empanelment survives the ending of it — and reviving a suspended
    row from the allow-list would silently undo every revocation any administrator has ever made, at
    the moment the revoked person next tries to sign in, with nothing on either screen to say it
    happened. Allow-listing grants an empanelment to somebody who never had one; it does not
    overturn a withdrawal. Those are two different decisions, and only an admin may make the second,
    through ``PATCH /designers/roster/{id}``, where it is visible as an act somebody took.

    **THE UPSERT WAS CONSIDERED AND REJECTED FOR EXACTLY THAT REASON.** ``db.designerroster.upsert``
    on the unique ``email`` would be one round trip instead of two and reads as the obvious way to
    write this. Its update arm IS the revival above. There is no formulation of an upsert here that
    does not either revive a suspended row or need a where-clause the client cannot express, so this
    is a find, a branch and a create.

    **IDEMPOTENT, AND SAFE UNDER TWO SIMULTANEOUS SIGN-INS.** The find-then-create below is
    check-then-act and nothing about it is atomic: a designer who opens the app on their phone and
    their laptop in the same second sends two logins that both read "no row" and both create one.
    What actually holds is the unique index on ``DesignerRoster.email``; the loser of that race gets
    a ``UniqueViolationError``, which is caught here and answered False. It is caught rather than
    propagated because the caller is the sign-in path: an unhandled driver error there reaches the
    catch-all in ``app/main.py`` and the designer is told the server broke, over a race whose
    outcome — a row exists, and it is theirs — is precisely what they were asking for. The prior
    read is kept all the same, because the ordinary case is a row that is already there, and letting
    the index refuse it instead would write a Postgres constraint error into the log on every single
    sign-in of every empanelled designer in the product.

    **THE EXISTENCE CHECK ASKS ABOUT THE MAILBOX, NOT ABOUT ONE SPELLING OF IT, AND THAT IS WHAT
    KEEPS THE RULE ABOVE TRUE FOR GMAIL.** With a plain ``find_unique`` on the canonical address,
    an old suspended ``sandy.craft3@gmail.com`` row would not be found when this function is called
    for ``sandycraft3@gmail.com``: it would see nothing, create a fresh ACTIVE row, and the
    revocation the whole function is built to preserve would have been undone by a dot — from the
    revoked person's own next sign-in, with two rows then on ``/admin/designers`` disagreeing about
    whether they may be here. :func:`email_match_keys` is what makes the check cover both spellings
    in the one query, and the row is CREATED under :func:`canonical_email` so that the next sign-in,
    however the address is spelled that day, lands on the row this one wrote.

    **``firstSeenAt`` IS DELIBERATELY LEFT NULL.** :func:`mark_roster_seen` runs later in the same
    login and writes only ``where firstSeenAt IS NULL``, so a stamp written here would consume that
    write. The column answers *"did the invitation ever reach them"*, and the approval path calls
    this function the moment an admin approves somebody — days before that person opens the app. A
    row stamped at creation would report every empanelment as accepted on the day it was granted,
    which is worse than no signal at all, because it looks like an answer.

    ``fullName`` and ``institution`` are left NULL too. They are admin-typed columns and this is not
    an admin typing: the display name on a Google profile is chosen by whoever owns that account,
    and ``access_roster`` refuses to store it for that reason. A roster screen showing a name nobody
    entered cannot be read as a record of what an administrator decided.

    ``actor_id`` is the administrator whose action caused this — the approver, on the allow-list
    path — and None where the empanelment was derived from the person's own sign-in, in which there
    is no actor to name and claiming one would be a fabricated audit trail.
    """
    keys = email_match_keys(email)
    if not keys:
        return False
    # THE MAILBOX, UNDER EVERY SPELLING THE TABLE COULD BE HOLDING IT — see the docstring. ``take``
    # is not needed and is not given: ``email`` is unique and ``keys`` holds at most two values, so
    # the query can return at most two rows and this only has to know whether it returned any.
    existing = await db.designerroster.find_first(where={"email": {"in": keys}})
    if existing is not None:
        # INCLUDING — ESPECIALLY — A SUSPENDED ONE. See the rule above. This early return is the
        # whole safety property of the function, which is why the test is a plain ``is not None`` on
        # the row and not something that inspects ``isActive``: there is no state an existing row
        # can be in that makes writing to it from here correct.
        return False
    # THE MAILBOX AND NOT THE SPELLING THAT HAPPENED TO REACH US. Writing the literal form here
    # would put a second unmatchable row in the table the moment somebody's Google account and
    # somebody's typing disagree about a dot, which is the failure the whole of Fix 2 is about.
    address = canonical_email(email)
    try:
        await db.designerroster.create(
            data={
                "email": address,
                "isActive": True,
                # Explicitly None, paired with ``isActive: True`` for the reason ``add_to_roster``
                # states: the roster screen reads the flag and the revocation date together, and
                # the two must never be able to disagree about whether this person may sign in.
                "revokedAt": None,
                "notes": note or DERIVED_EMPANELMENT_NOTE,
                "addedById": actor_id,
            }
        )
    except UniqueViolationError:
        # The other login won. A row exists and it admits them, which is the outcome both callers
        # wanted — but this call did not create it, so it must not report that it did.
        return False
    return True


async def mark_roster_seen(email: Any) -> None:
    """Stamp ``firstSeenAt`` the first time an empanelled email actually signs in.

    An admin adds five designers to the roster in March and has no way, in April, to tell which
    of them ever opened the app: an invitation that never arrived looks exactly like one that was
    ignored. This is that signal, and it is written once — the WHERE clause carries
    ``firstSeenAt: None``, so the stamp records the FIRST sign-in rather than the most recent one
    and two simultaneous logins cannot race each other into overwriting it.

    Silent when no roster row exists. It is called on every successful sign-in, including the
    admins' — an ``update_many`` that matches nothing is cheaper than deciding beforehand whether
    to ask, and one query on a path a user takes once a week costs nothing worth optimising.

    **MATCHED ON THE MAILBOX, LIKE THE GATE THAT JUST ADMITTED THEM.** Keying this on the literal
    address alone would produce the quietest possible bug: :func:`roster_allows` lets a designer in
    on the strength of a row spelled slightly differently, this write matches nothing, and the admin
    who added them reads a permanently blank "first seen" for somebody who has been using the app
    for months — then concludes the invitation never arrived and chases them about it. Where two
    spellings of one mailbox both have rows, both are stamped; they describe the same person's
    arrival and leaving one blank would be the same wrong answer in a narrower form.
    """
    keys = email_match_keys(email)
    if not keys:
        return
    await db.designerroster.update_many(
        where={"email": {"in": keys}, "firstSeenAt": None},
        data={"firstSeenAt": datetime.now(UTC)},
    )


def roster_payload(row: Any) -> dict[str, Any]:
    """One roster row as the admin screen reads it."""
    return {
        "id": row.id,
        "email": row.email,
        "fullName": row.fullName,
        "institution": row.institution,
        "notes": row.notes,
        "isActive": row.isActive,
        "revokedAt": _iso(row.revokedAt),
        "firstSeenAt": _iso(row.firstSeenAt),
        "createdAt": _iso(row.createdAt),
        "updatedAt": _iso(row.updatedAt),
        "addedById": row.addedById,
    }


# --------------------------------------------------------------------------------------
# The profile
# --------------------------------------------------------------------------------------


async def get_or_create_profile(user_id: str) -> Any:
    """The user's profile row, created empty if they have never saved one.

    An upsert rather than a find, so ``GET`` and ``PUT`` cannot disagree about whether the row
    exists. The alternative — returning ``{}`` for a missing profile, as ``/preferences/me``
    does — was rejected here because the profile is READ by workshop creation, and a create path
    that has to handle "no row yet" as well as "row with no values" is two paths where one will
    do.

    ``include`` IS NOT OPTIONAL HERE even though the upsert asks for nothing new — see
    :data:`PROFILE_INCLUDE`. Without it this function answers a row whose ``location`` is ``None``
    because it was never fetched, which is indistinguishable from a designer who has no address.
    """
    return await db.designerprofile.upsert(
        where={"userId": user_id},
        data={"create": {"user": {"connect": {"id": user_id}}}, "update": {}},
        include=PROFILE_INCLUDE,
    )


async def update_profile(user_id: str, values: dict[str, Any]) -> Any:
    """Write the given profile columns, leaving every column not named here alone.

    ``values`` is expected to come from ``payload.model_dump(exclude_unset=True)`` — see the
    module docstring of ``app.schemas.designers`` for why absent and null have to mean different
    things on this particular body. It is also expected to have been through
    :func:`app.services.records.attach_location`, which is where a ``location`` OBJECT on the body
    becomes the ``locationId`` this function writes; the route does that, exactly as all six
    field-record routes do it, so that there is one implementation of "store an address" in the
    repository rather than a seventh.

    THE THREE ANSWERS THIS LOOP KEEPS APART, because two of them look the same from a distance and
    the difference is the whole contract of this body:

    * **absent** — the key is not in ``values`` at all, so the ``continue`` below fires and the
      stored column is untouched. This is what an admin's two-key PUT relies on, and what a client
      that has not been rebuilt yet relies on for a column it has never heard of.
    * **present and null** — written as NULL. This is how a designer un-answers a question, and it
      is why ``exclude_unset`` above is load-bearing rather than tidy.
    * **present and 0** — written as 0, because ``0 is not None``. "No odd months" and "nobody was
      ever asked" are different statements about ``experienceMonths``, and nothing in this loop
      folds one into the other. (Note the ``or None`` in the string branch does NOT apply to an
      int: it is reached only ``if isinstance(value, str)``, so a 0 cannot be swallowed by it.)
    """
    data: dict[str, Any] = {}
    for key in PROFILE_FIELDS:
        if key not in values:
            continue
        value = values[key]
        if key in PROFILE_DATE_FIELDS:
            data[key] = _parse_date(value)
        elif isinstance(value, str):
            # Trimmed, and an all-whitespace value stored as NULL rather than as " ". A profile
            # whose institution is a single space is not blank to any `if row.institution` test
            # in this codebase, so it would print as an empty line on the cover of every report.
            data[key] = value.strip() or None
        else:
            data[key] = value
    # ── THE ADDRESS RELATION, MERGED OUTSIDE THE LOOP ABOVE, AND OUTSIDE IT ON PURPOSE ──────────
    #
    # ``attach_location`` has already created the ``Location`` row and left its id here under
    # ``locationId``. That key is NOT in ``PROFILE_FIELDS`` and must never be put there — see the
    # note on that tuple for the two guard tests that fail the moment it is.
    #
    # ABSENT STILL MEANS KEEP, exactly like every column above. ``attach_location`` sets this key
    # only when the body actually carried a location, so a save that mentions no address leaves the
    # stored one standing; and there is no branch here that can write NULL over it, because
    # ``forbid_clearing_location`` on the body refuses the explicit null that would be the only way
    # to ask for that. A designer who moves house REPLACES their location; they cannot delete it.
    if "locationId" in values:
        data["locationId"] = values["locationId"]
    if not data:
        return await get_or_create_profile(user_id)
    return await db.designerprofile.upsert(
        where={"userId": user_id},
        data={"create": {**data, "user": {"connect": {"id": user_id}}}, "update": data},
        include=PROFILE_INCLUDE,
    )


def profile_payload(row: Any) -> dict[str, Any]:
    """One profile as the clients read it.

    ── TWO ADDRESSES ON ONE PAYLOAD: WHICH IS AUTHORITATIVE, DECIDED HERE RATHER THAN PER READER ─

    Since ``locationId`` was added, a profile can carry an address in two places, and this payload
    returns BOTH of them, side by side, verbatim and unmerged: the flat ``addressLine``/``city``/
    ``state``/``pincode`` columns in the loop above, and the ``Location`` row under ``location``.
    Nothing here falls back from one to the other and nothing here copies one into the other.

    **THE FLAT COLUMNS ARE AUTHORITATIVE FOR THE FOUR FACTS THEY CAN HOLD.** They are where every
    live row's address actually is: the migration that added the relation deliberately backfilled
    NOTHING, because ``Location.latitude``/``longitude`` are NOT NULL and manufacturing a coordinate
    for an address that never had one is the precise invention ``Location``'s own docstring exists to
    refuse. They are also the four :data:`PREFILL_MAP` copies into stage 3 of every report. So a
    reader asking "what is this designer's postal address" reads them, and a client rendering one
    address block renders them.

    **``location`` IS AUTHORITATIVE FOR THE TWO FACTS NOTHING ELSE CAN HOLD** — the DISTRICT and the
    COORDINATE. There is no flat column for either; that absence is the whole reason the relation was
    added, and it is why ``location.district`` is never "a second spelling" of anything above.

    **AND NEITHER IS SILENTLY PREFERRED, WHICH IS THE DECISION AND NOT AN OMISSION.** Merging them
    would need a precedence rule, and every precedence rule invents an answer for the rows where the
    two disagree — a designer who moved house and corrected one of the two forms — while a merged
    payload cannot say which side it came from. The cost is stated instead of hidden, in the same
    words ``DesignerProfile.addressLine`` uses in schema.prisma: until the retiring migration moves
    the values across, a profile may carry an address in either place, so **a client that shows only
    one of the two will show some designers a blank where their address is.** Show both.

    ``location`` IS ENCODED THE WAY THE SIX FIELD-RECORD ROUTES ENCODE IT — ``jsonable_encoder`` over
    the Prisma row, which is literally ``public_encode``'s first step — so ``profile.location`` and
    ``artisan.location`` are the same object on the wire and one component can render both. The
    redaction half of ``public_encode`` is deliberately not reached for: ``Location`` carries no
    identity number and no media URL, and pretending otherwise would suggest to the next reader that
    something on that table is masked.

    ``locationId`` IS PUBLISHED BESIDE IT so a client can tell "this designer has no address row"
    (null) from "this response did not load the address row" — which are indistinguishable from
    ``location`` alone, and the second of which is exactly the failure :data:`PROFILE_INCLUDE`
    exists to prevent.
    """
    payload: dict[str, Any] = {"id": row.id, "userId": row.userId}
    for key in PROFILE_FIELDS:
        value = getattr(row, key, None)
        payload[key] = _iso(value) if isinstance(value, datetime) else value
    payload["locationId"] = getattr(row, "locationId", None)
    location = getattr(row, "location", None)
    payload["location"] = jsonable_encoder(location) if location is not None else None
    payload["createdAt"] = _iso(row.createdAt)
    payload["updatedAt"] = _iso(row.updatedAt)
    return payload


# --------------------------------------------------------------------------------------
# Prefill
# --------------------------------------------------------------------------------------

#: Profile column -> registry field key. The report never learns that a profile exists; it reads
#: ordinary stage data written under these keys, which is what keeps the designer's details
#: editable per workshop like every other captured value.
#:
#: ── EVERY WRITABLE COLUMN IS CARRIED, WHICH IS THE OWNER'S INSTRUCTION OF 2026-08-25 ────────────
#:
#: It used to be four of the twenty. The instruction is that everything typed on the Designer Page
#: is master data pre-filled into EVERY report, so the honest shape of this table is "all of them",
#: and the way that is kept honest is
#: ``test_every_writable_profile_column_is_either_prefilled_or_named_here``: a column added to
#: :data:`PROFILE_FIELDS` must either appear below or be listed in that test's explicit exemptions,
#: so the next column somebody adds cannot silently fail to reach a report. Widening it four at a
#: time, by hand, with nothing checking, is how it came to be four out of twenty in the first place.
#:
#: ONE COLUMN IS EXEMPT AND IT IS THE MECHANISM WORKING, NOT AN OVERSIGHT — AND IT IS NOT A "NEVER".
#: ``experienceMonths`` was added to :data:`PROFILE_FIELDS` on 2026-08-29 and is NOT below, because
#: the stage-3 box it would copy into does not exist yet: there is no ``designerExperienceMonths``
#: field, and declaring one moves ``registry_version()``, owes a re-dump of the bundled Android
#: registry asset and a re-cut APK, and settles how a report PRINTS the pair — a report-layout
#: decision rather than a consequence of adding a column to a form. So it sits in ``PREFILL_EXEMPT``
#: in ``tests/test_designer_prefill_contract.py``, which carries the three steps that retire the
#: entry and the sentence describing what it costs meanwhile: the months save and read back, and the
#: report goes on printing the years alone. THAT IS THE WHOLE POINT OF THE EXEMPTION LIST — a gap
#: that is written down where the guard test reads it, rather than a column quietly missing from the
#: table below where it is indistinguishable from the omission this module exists to catch. A future
#: column that genuinely must not cross (a private note, an internal flag) belongs in the same place,
#: with its own reason.
#:
#: THE TARGETS ARE REAL REGISTRY FIELDS AND ARE CHECKED. ``validate_registry`` cannot see this
#: table — it is not part of the registry — so
#: ``test_every_prefilled_profile_column_has_a_receiving_field`` resolves every right-hand key
#: against ``STAGES`` and fails the build on a typo. Without it a misspelt target is a value written
#: into a stage blob under a key no form renders and no report prints: saved, and invisible.
PREFILL_MAP: tuple[tuple[str, str], ...] = (
    ("displayName", "designerName"),  # stage 1, workshopSetup
    ("institution", "designerInstitution"),  # stage 1, workshopSetup
    ("biography", "designerProfile"),  # stage 3, workshopPlan
    ("experienceYears", "designerExperience"),  # stage 3, workshopPlan
    # The rest of stage 3's `workshopPlan` — see the block comment at those fields for why the
    # designer's details live on stage 3 rather than on stage 1's cover table.
    ("localName", "designerLocalName"),
    ("designation", "designerDesignation"),
    ("department", "designerDepartment"),
    ("qualification", "designerQualification"),
    ("specialisation", "designerSpecialisation"),
    ("phone", "designerPhone"),
    ("email", "designerEmail"),
    ("website", "designerWebsite"),
    ("addressLine", "designerAddress"),
    ("city", "designerCity"),
    ("state", "designerState"),
    ("pincode", "designerPincode"),
    ("empanelmentNo", "designerEmpanelmentNo"),
    ("empanelmentDate", "designerEmpanelmentDate"),
    ("photoMediaId", "designerPhoto"),
    ("signatureMediaId", "designerSignature"),
    ("cvMediaId", "designerCv"),
)


async def prefill_from_profile(user_id: str) -> dict[str, Any]:
    """The stage-1 and stage-3 values a workshop this user creates should START with.

    Returns ``{registry field key: value}`` for every pair in :data:`PREFILL_MAP` the profile can
    actually answer, and an empty dict for a designer who has never filled one in. Which pairs
    those are is that table's business and is deliberately not restated here — this docstring said
    "``designerName``, ``designerInstitution``, ``designerProfile``, ``designerExperience``" for as
    long as those were the only four, and a prose list of a table's contents is a second copy that
    goes stale the first time the table grows. It grew on 2026-08-25.

    **THESE ARE COPIES, AND THEY MUST STAY COPIES.** A report is a HISTORICAL DOCUMENT. It records
    a workshop that was run, on given dates, by a named person working out of a named institution
    at the time. If a workshop's stages held a reference to the profile instead of a copy of its
    values, then a designer who moves from NIFT to NID in 2027 would retroactively rewrite the
    2026 report — regenerating it, or merely previewing it, would name an institution that had
    nothing to do with the workshop and had never sponsored it. The same is true of the biography
    and of the years of experience, which is a number the report prints as a statement about the
    designer *on the day*. Copying is not an optimisation here; referencing would be a
    falsification. Every value below is read once, at creation, and never consulted again — the
    stages own them from that moment, and a designer correcting the spelling of their name in one
    workshop must not touch any other.

    ``displayName`` falls back to the account's own name, because a designer who has filled in
    nothing at all still has a name and retyping it into stage 1 of every workshop is exactly the
    chore this exists to remove.
    """
    profile = await db.designerprofile.find_unique(
        where={"userId": user_id}, include={"user": True}
    )
    if profile is None:
        return {}

    values: dict[str, Any] = {}
    for column, field_key in PREFILL_MAP:
        value = getattr(profile, column, None)
        if isinstance(value, str):
            value = value.strip()
        # A DateTime COLUMN LANDING IN A `DATE` FIELD MUST BE NARROWED HERE, NOT BY `str()` LUCK.
        #
        # `empanelmentDate` is the one date in this table, it is a Postgres DateTime, and its target
        # `designerEmpanelmentDate` is a registry DATE — which `coerce_value` reads as
        # `str(raw).strip()[:10]`. That happens to work on both `str(datetime)` ("2026-08-25 00:00…")
        # and `datetime.isoformat()` ("2026-08-25T00:00…"), and relying on it would be relying on the
        # first ten characters of a repr. Narrowed explicitly so the stored value is a date string
        # exactly as every client sends one, and so a second date column added to this table later
        # is carried by this branch rather than by the same coincidence.
        if isinstance(value, datetime):
            value = value.date().isoformat()
        if value in (None, ""):
            continue
        values[field_key] = value

    if "designerName" not in values:
        account_name = str(getattr(getattr(profile, "user", None), "name", "") or "").strip()
        if account_name:
            values["designerName"] = account_name
    return values


# --------------------------------------------------------------------------------------
# Private helpers
# --------------------------------------------------------------------------------------


def _iso(value: Any) -> str | None:
    return value.isoformat() if isinstance(value, datetime) else None


def _parse_date(raw: Any) -> datetime | None:
    """An ISO date string as a UTC datetime, or None for anything unreadable.

    Unreadable input becomes NULL rather than a 422 for the same reason ``_parse_date`` in the
    design-workshop routes does: this is one optional identifier on a twenty-two-field profile,
    and refusing the whole save because the empanelment date was typed ``12/03/2026`` would lose
    the twenty-one fields the designer got right.
    """
    if raw in (None, ""):
        return None
    if isinstance(raw, datetime):
        return raw if raw.tzinfo else raw.replace(tzinfo=UTC)
    try:
        return datetime.fromisoformat(str(raw)[:10]).replace(tzinfo=UTC)
    except ValueError:
        return None

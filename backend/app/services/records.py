from collections.abc import Sequence
from datetime import UTC, datetime
from decimal import Decimal
from typing import Any, NamedTuple

from fastapi import HTTPException, status
from fastapi.encoders import jsonable_encoder

from app.core.db import db
from app.services.artisan_identity import mask_aadhaar
from app.services.concurrency import gather_reads
from app.services.measurement_provenance import MARKER_BODY_KEY, method_stamps
from app.services.rich_text import search_needles
from app.services.text_format import title_case_fields
from prisma import Json

# Keys that must never leave the API, no matter how deeply nested inside an embedded relation
# (e.g. a media file's ``uploadedBy`` user, or a record's ``createdBy``).
_SENSITIVE_KEYS = {"passwordHash"}

# Regulated identity numbers. Unlike a password hash these are MASKED rather than dropped: a
# researcher still has to be able to confirm they are looking at the right person, and
# "XXXX XXXX 9012" is enough for that while not being a usable identifier.
#
# Both columns exist on Artisan and nowhere else, so an encoded dict carrying either key IS an
# artisan — which means it also carries its own ``createdById``, and the entitlement decision can be
# made per node without the walk knowing anything about the shape it is walking.
#
# THREE ENCODED SHAPES CARRY THESE KEYS WITHOUT BEING AN ARTISAN ROW, all three measured rather than
# imagined, and this comment claimed there was ONE until an audit widened the count. Every one of
# them is a blob KEYED BY COLUMN NAME, which is what makes a column-name rule mis-fire on it:
#   * ``RecordRevision.changes`` — an audit entry for a retracted Aadhaar arrives as
#     ``{"aadhaarNumber": {"old": ..., "new": ...}}``, so the key holds a dict, not a number;
#   * ``extraMetadata.fieldProvenance`` — ``merge_field_provenance`` stamps every field it saw
#     CHANGE, ``aadhaarNumber`` included (it is not in :data:`PROVENANCE_SKIP_FIELDS`), so an edited
#     identity column leaves ``{"aadhaarNumber": {"by": ..., "byName": ..., "at": ...}}`` behind;
#   * the REST of ``extraMetadata`` — a client-writable Json column that ``merge_field_provenance``
#     merges from the request body and ``public_encode`` echoes whole. Anything a caller puts under
#     these two key names inside it arrives here, in any shape it likes.
# See :func:`_mask_identity_node` for what each of the three gets and why. The scalar case — the
# artisan row itself — is still the overwhelmingly common one and is masked at the call site.
_IDENTITY_KEYS = ("aadhaarNumber", "pehchanCardNumber")

# THE FIELDS THAT HAND OVER BYTES. A ``MediaFile.url`` is not a description of a file, it IS the file:
# it is a fetchable object URL, stored on the row and set from ``s3.public_url_for_key``. Anyone
# holding it can save the photograph or the recording, with no further call into this API.
#
# WHY THIS EXISTS AT ALL. It did not need to while reading the repository was owner-scoped: whoever
# could see a media ROW could already download that uploader's data by definition, so the two were the
# same entitlement and nobody had to say so. Opening reads to every signed-in account (see the banner
# above ``viewable_where``) split them apart, and the repository's rule is that everybody may LOOK at
# every record while taking data out stays earned. Left alone, ``GET /media`` and ``GET /search`` would
# have become an index of direct download links to every file in the repository — which is precisely
# the half of the rule that is not open.
#
# So the ROW still travels for everybody: the filename, the type, the caption, the duration, which
# record it belongs to, who uploaded it. Only the bytes are withheld, and only from callers who may
# not take that uploader's data.
#
# THE TRANSCRIPT IS BYTES, NOT A CAPTION, and this list said otherwise until 2026-08-15. The sentence
# above used to read "the caption, THE TRANSCRIPT", and it was wrong in a way that made the narrowest
# control in this repository decoration. ``MediaFile.transcriptText`` is the verbatim text of an
# artisan interview — names, incomes, family circumstances, the testimony itself — and it is the
# column the report's transcript annexure prints as the artisan's words. Withholding the recording
# while shipping its full text protects nothing: a CROWDSOURCE_VOLUNTEER (rank 10, the authentication
# floor) calling ``GET /api/media?mediaType=AUDIO&pageSize=100`` was handed every interview in the
# repository as text, and the co-designer refused a colleague's clip by
# ``workshop_transcripts.load_transcript_items`` — which calls that same read "a leak" — got it back
# by lifting the media id out of the stage he can already edit and calling ``GET /api/media/{id}``.
# Two live rules, and the permissive one won on every path a client had. Settled here on the
# RESTRICTIVE side, because that is the side the documentation already tells a reviewer is held:
# docs/PERMISSIONS.md §4.4.1's "does not confer" column says reading the text behind a recording this
# account may not read "is gated per media file by ``owned_or_granted_where(uploadedById)``". One
# request disproved that sentence; this makes it true again rather than deleting it.
#
# WHY THE SAME PREDICATE AND NOT A SECOND ONE. The transcript is derived from the file; anyone
# entitled to the file can run the same provider over it. Giving the text its own gate would be a
# third rule in a domain that has just been burned by having two — and the surface a co-designer
# actually needs, ``GET /design-workshops/{id}/transcripts``, has its own WIDER predicate
# (``owned_or_granted_where``, whose third clause admits a ``DesignWorkshopViewer``), so nothing that
# was reachable through the feature built for it becomes unreachable here.
#
# ``objectKey`` IS IN THIS LIST, and it has to be. ``s3.public_url_for_key`` is deterministic — the URL
# is the CDN host plus the key, and the host is public knowledge — so handing over the key hands over
# the file just as surely as handing over the URL, one string concatenation later. Withholding one and
# not the other would be a lock on the door beside an open window. Nothing reads ``objectKey`` off a
# LISTED row (the upload flow gets its key from the presign response, which is the caller's own object
# by construction), so removing it from a read response costs no client anything.
_MEDIA_URL_KEYS = ("url", "publicUrl", "objectKey")

#: The transcript columns, withheld under the SAME entitlement as the URL — see the banner above.
#: Kept as their own tuple rather than folded into ``_MEDIA_URL_KEYS`` so the name of each list still
#: describes what is in it: one is "the ways to fetch the object", the other is "the object's content
#: in text". They are dropped together by ``_MEDIA_TAKEABLE_KEYS`` and must stay that way.
_TRANSCRIPT_KEYS = ("transcriptText", "transcriptSummary")

#: Everything a media node carries that IS the recording rather than a description of it. This is the
#: tuple ``_redact_sensitive`` actually pops; the two above exist to say why each member is in it.
_MEDIA_TAKEABLE_KEYS = (*_MEDIA_URL_KEYS, *_TRANSCRIPT_KEYS)

# How a node is recognised as a media file without the walk knowing what shape it is walking:
# ``objectKey`` exists on MediaFile and on no other model, and a media node also carries its own
# ``uploadedById``, so the entitlement decision can be made per node. Exactly the trick
# ``_IDENTITY_KEYS`` uses with ``createdById``.
_MEDIA_MARKER = "objectKey"

#: Passed as ``media_urls`` to mean "every URL may travel" — a professor, an admin, or a surface that
#: has already gated itself (``datasets.py`` behind ``require_dataset_admin``). NOT the holder of the
#: grantable ``canDownloadDataset`` boolean: see ``media_url_owners`` for why that term was removed.
ALL_MEDIA_URLS = None


class _Unset:
    """A sentinel distinct from ``None``, because ``None`` is a MEANINGFUL value for ``media_urls``.

    ``media_urls=None`` means "allow every URL". So "the caller did not say" cannot also be ``None``,
    or the safe default and the most permissive setting would be spelled identically — and a route that
    simply forgot to think about it would get the widest answer.
    """

    def __repr__(self) -> str:  # pragma: no cover - debugging aid only
        return "<unset>"


_UNSET = _Unset()


def derive_age(date_of_birth: Any, *, on: datetime | None = None) -> int | None:
    """Whole years between ``date_of_birth`` and today. None when there is no usable date.

    **AGE IS DERIVED AND NEVER STORED**, which is the entire reason ``Artisan.dateOfBirth`` is a date
    rather than the age column the workshop's participant table asks for. An age written down is
    wrong within a year and nothing in this system would ever notice: a record entered as "42" reads
    42 for the rest of its life, in every report it is printed in. Computing it here means it is
    right on the day it is printed and right again next year, on the same row, with nobody editing
    anything.

    ``on`` exists so a test can ask what this returns on a stated day rather than on the day the
    test happens to run — an age function tested against ``now()`` passes in March and fails in
    September, on the birthday of whatever fixture it uses.

    Returns None rather than 0 for a missing, unparseable or future date: a blank box and "zero
    years old" are different statements, and the second is one this repository would be making up.
    """
    if not date_of_birth:
        return None
    if isinstance(date_of_birth, str):
        try:
            date_of_birth = datetime.fromisoformat(date_of_birth.replace("Z", "+00:00"))
        except ValueError:
            return None
    born = getattr(date_of_birth, "date", lambda: date_of_birth)()
    today = (on or datetime.now(UTC)).date()
    # The birthday-not-yet-reached correction, spelled out rather than divided: (today - born).days
    # // 365 drifts by a day every four years and reports somebody as a year older than they are for
    # a few days around their birthday, which is exactly the kind of wrongness nobody checks.
    years = today.year - born.year - ((today.month, today.day) < (born.month, born.day))
    return years if 0 <= years <= 130 else None


def derive_experience_years(craft_start: Any, *, on: datetime | None = None) -> int | None:
    """Whole years between ``craft_start`` and today. None when there is no usable date.

    THE SIBLING OF :func:`derive_age`, DELIBERATELY IDENTICAL IN SHAPE. ``Artisan.craftStartDate``
    is the date an artisan began practising the craft, and ``Artisan.experienceYears`` is the older
    column that holds a number somebody stated instead. The number is right on the day it is
    written and wrong from then on -- the schema comment on ``experienceYears`` says so in terms,
    "an artisan documented in 2024 with 30 years reads 30 in 2030" -- and it is a TABLE_COLUMN in
    the participant table of every workshop report, so that decay prints. A date does not decay.

    WHY IT IS A SEPARATE FUNCTION AND NOT ``derive_age`` WITH A WIDER BAND. The band is the whole
    difference and it is load-bearing: ``participant.experienceYears`` declares
    ``min_value=0, max_value=90`` and ``ArtisanCreate.experienceYears`` declares ``ge=0, le=90``, so
    a number outside that range is not a value this system can carry. ``stage_schema.coerce_value``
    returns an ERROR for an out-of-range answer and ``validate_entry`` re-coerces EVERY field on
    EVERY save, not only the ones a designer touched -- so a hydrated 91 would become a refused
    answer counted in ``refusedAnswers`` on a box nobody typed in. Returning None instead means the
    box stays blank and the legacy fallback behind it still gets its turn. (``participant.age`` has
    exactly this defect today with its own ``min_value=10``: a derived age of 8 is refused rather
    than dropped. It is pre-existing, it is not repaired here, and it is reported to the owner
    rather than copied.)

    Returns None rather than 0 for a missing, unparseable, future or out-of-band date, on
    :func:`derive_age`'s reasoning: a blank box and "practising for zero years" are different
    statements. Note the difference from the age band, which starts at 0 for a real reason -- a
    newborn has an age and nobody has negative experience. Zero years here IS reachable and IS
    kept: an apprentice who started this month is a real answer, which is why every reader of this
    value tests ``is not None`` rather than truthiness.

    ``on`` exists for the reason it exists on :func:`derive_age` -- a derivation tested against
    ``now()`` passes in March and fails in September.
    """
    if not craft_start:
        return None
    if isinstance(craft_start, str):
        try:
            craft_start = datetime.fromisoformat(craft_start.replace("Z", "+00:00"))
        except ValueError:
            return None
    started = getattr(craft_start, "date", lambda: craft_start)()
    today = (on or datetime.now(UTC)).date()
    # The anniversary-not-yet-reached correction, spelled out rather than divided, for the reason
    # written out in `derive_age`: `(today - started).days // 365` drifts a day every four years.
    years = today.year - started.year - ((today.month, today.day) < (started.month, started.day))
    return years if 0 <= years <= 90 else None


def mask_identity_number(value: Any) -> Any:
    """The masked form of an artisan identity number.

    ``mask_aadhaar`` is reused verbatim for the Pehchan card: its rule is "keep the last four
    characters, X out everything before them, and mask anything shorter than four entirely", which is
    right for both numbers — and reusing it means one artisan's identity reads identically on every
    surface instead of gaining a second spelling.
    """
    return mask_aadhaar(value)


#: What a nested identity value is replaced with. NOT ``mask_identity_number``'s "XXXX XXXX 9012":
#: see :func:`_mask_identity_node` for why the four digits are deliberately withheld one level down.
_NESTED_IDENTITY_MASK = "XXXX XXXX XXXX"


#: The keys a ``fieldProvenance`` stamp can have, and no others: ``{by, byName, at}`` from
#: :func:`merge_field_provenance` plus whatever ``MeasurementProvenance.stamp`` adds for a dimension.
#: Retyped rather than imported because the measurement half is built key by key inside that method
#: and has no constant to import — and the direction of a mismatch is safe: a stamp carrying a key
#: this set has not heard of is not recognised as a stamp and is masked whole, which loses a "who
#: filled this in" line rather than leaking anything.
_PROVENANCE_STAMP_KEYS = frozenset(
    {
        "by",
        "byName",
        "at",
        "method",
        "methodProvider",
        "methodModelId",
        "methodConfidence",
        "methodTechnique",
    }
)


def _mask_identity_node(node: Any) -> Any:
    """What a CONTAINER under an identity key is served as. Never reached for a scalar.

    A scalar under one of :data:`_IDENTITY_KEYS` is the artisan column, and the call site hands that
    straight to ``mask_identity_number`` — byte for byte as it always has, "XXXX XXXX 9012" and all.
    A container is one of the three column-name-keyed blobs named above that set, and this function
    exists because ``mask_identity_number`` normalises with ``str(value)`` before it slices, so
    handing it a dict returned the last few characters of the dict's *repr*: a
    ``RecordRevision.changes`` entry for a retracted Aadhaar came out of
    ``GET /api/data-access/revisions`` as the literal string ``"XXXX XXXX rue}"`` — the tail of
    ``…'redacted': True}`` with the spaces stripped. That is not a leak, but it destroyed the entry
    on BOTH readers of this blob — ``frontend/components/CollabPanel.tsx`` renders
    ``String(change.old ?? "—")`` and Android's ``MainActivity.RecordCollabSection`` renders
    ``jsonText(change.old)``; a string has neither ``.old`` nor an ``old`` member, so the row an admin
    opened the edit history to find printed as ``— → —``. The screens that exist to show that
    something was done to a record showed that nothing was.

    Three recognised shapes, then a total mask. THE DEFAULT IS THE TOTAL MASK, and that is the whole
    safety property: no leaf value, and no dict KEY, is echoed out of a container under an identity
    key unless this function recognised the container.

    * AN ENTRY THAT IS ALREADY ONE OF ``access._redacted_change``'s — exact key set, ``redacted``
      True, AND an ``(old, new)`` pair in ``access.REDACTED_PLACEHOLDER_PAIRS`` — is returned
      untouched. It contains no value by construction, and masking a placeholder as an identity
      number ("XXXX XXXX ded)") is the same destruction with better spelling. THE PAIR IS CHECKED
      AGAINST THE CLOSED SET RATHER THAN THE FLAG BELIEVED, because ``extraMetadata`` is
      client-writable: a bare ``{"redacted": true, "note": "123456789012"}`` under this key name used
      to be returned verbatim, which is 12 digits echoed by the function whose job is that they are
      not.
    * ANY OTHER AUDIT-SHAPED ENTRY (keys within ``{old, new, redacted}``, at least one of the two
      present) is RE-DERIVED through ``access.redacted_placeholder``, which reads only whether each
      side was empty. Those are the historical ledger rows written before
      ``access.REVISION_REDACTED_FIELDS`` existed, which still hold real retracted numbers, and this
      is what they are worth: no digit of the stored value crosses, and the served row reads exactly
      like one written today. Re-deriving rather than masking both sides is also the fix for the
      REPLACEMENT row — ``{"old": "1111…", "new": "4444…"}`` masked twice is
      ``"XXXX XXXX XXXX" → "XXXX XXXX XXXX"``, legible and reading as though nothing changed, which
      is the exact failure ``_redacted_change``'s four distinct wordings exist to prevent.
      Serving the last four instead (what every other surface shows) is a one-line change and an
      OWNER's call: it is the difference between an admin being able to trace a duplicate artisan
      back to the number that freed the unique key, and not. THE DEFAULT HERE IS STILL NOTHING, AND
      THE REASON IT IS NO LONGER THE REASON IT WAS. This read "House rule 5 has no 'but only a
      little' clause, so the default here is nothing" -- and on 2026-08-24 the owner decided that
      for the Aadhaar this repository does precisely "but only a little": the masked last four now
      cross into a design workshop's participant roster and into the ministry document built from it
      (``stage_definitions``' ``participant.aadhaarNumber``, where the decision is written out in
      full). An appeal to the rule therefore no longer settles this, and leaving the old sentence
      standing would have let it go on appearing to.

      WHAT STILL SETTLES IT IS NARROWER AND IS ABOUT THIS BLOB RATHER THAN ABOUT THE NUMBER. A
      historical ledger row is the ONLY surviving copy of a value somebody retracted; four digits of
      it is a disclosure with no consent behind it, on a surface no form ever displayed it on, to an
      audience nobody named -- where the workshop carry is a copy the owner authorised, for a stated
      audience, of a record that was not retracted. Same number, two surfaces, two decisions; this
      one has not been made. ``access.REVISION_REDACTED_FIELDS`` records the same question as
      re-raised, for the same reason.
    * A ``fieldProvenance`` STAMP (:data:`_PROVENANCE_STAMP_KEYS`) is returned untouched. It holds
      who/when, never the value, and it is the one of the three shapes that is NOT client-writable:
      ``merge_field_provenance`` drops ``fieldProvenance`` from the incoming body and from the stored
      seed, so every stamp reaching here was composed by this server. Masking it was a live defect
      rather than caution — the stamp for an edited ``aadhaarNumber`` reached
      ``FieldProvenance.tsx`` with ``byName`` and ``at`` replaced by ``"XXXX XXXX XXXX"``, i.e. the
      provenance panel naming a mask as the person who filled the field in.

    Anything else — any other dict, any list, and every leaf inside them — becomes
    :data:`_NESTED_IDENTITY_MASK` in full. That is deliberately blunter than walking: a walk that
    replaced only ``str`` leaves left ``{"old": 987654321098}`` untouched (integers are not strings),
    and a walk that replaced every leaf still echoed the dict's KEYS. Nothing in this repository puts
    a structure worth preserving under one of these two key names, so the shape is not worth a single
    digit.
    """
    from app.services.access import (
        REDACTED_CHANGE_KEYS,
        REDACTED_PLACEHOLDER_PAIRS,
        redacted_placeholder,
    )

    if isinstance(node, dict):
        keys = set(node)
        if (
            keys == REDACTED_CHANGE_KEYS
            and node.get("redacted") is True
            and (node.get("old"), node.get("new")) in REDACTED_PLACEHOLDER_PAIRS
        ):
            return node
        if keys <= REDACTED_CHANGE_KEYS and ("old" in keys or "new" in keys):
            return redacted_placeholder(node.get("old"), node.get("new"))
        if keys and keys <= _PROVENANCE_STAMP_KEYS:
            return node
    return _NESTED_IDENTITY_MASK


def _redact_sensitive(
    value: Any,
    viewer_id: str | None,
    unmasked: bool,
    media_urls: set[str] | None = None,
) -> Any:
    """Recursively scrub an already-encoded payload of everything that must not leave the API.

    Mutates in place and returns the same object. Three jobs:

    * password hashes are dropped outright, however deeply nested;
    * identity numbers are masked unless ``unmasked`` (professor and above) or the node's own
      ``createdById`` is the viewer — entitlement follows the ARTISAN, not the payload. An identity
      key holding a CONTAINER rather than a number is not an identity number at all but one of the
      three column-name-keyed blobs listed above :data:`_IDENTITY_KEYS`; it goes through
      :func:`_mask_identity_node`, which serves the two server-written shapes legibly and masks
      anything else WHOLE rather than flattening it into the tail of its own repr;
    * media URLs AND transcript text (:data:`_MEDIA_TAKEABLE_KEYS`) are dropped unless ``media_urls``
      is ``None`` (all allowed) or contains the node's own ``uploadedById`` — entitlement follows the
      FILE'S UPLOADER, for the same reason.

    Both per-node tests read an owner column off the node being walked, which is what lets one pass
    over an arbitrary shape make a per-record decision without knowing what shape it is.
    """
    if isinstance(value, dict):
        for key in _SENSITIVE_KEYS:
            value.pop(key, None)
        if not unmasked and not (viewer_id and value.get("createdById") == viewer_id):
            for key in _IDENTITY_KEYS:
                if key in value:
                    column = value[key]
                    # A container under an identity key is an audit entry or a provenance stamp,
                    # not a number. Masking it as if it were one flattens it to the tail of its own
                    # repr — see :func:`_mask_identity_node` for the three shapes and what each
                    # gets. Anything it does not recognise is masked whole, keys included.
                    value[key] = (
                        _mask_identity_node(column)
                        if isinstance(column, (dict, list))
                        else mask_identity_number(column)
                    )
        # The marker is READ before the keys are dropped, which matters because ``objectKey`` is both
        # the marker and one of the keys.
        # Dropped rather than blanked. A key present and null reads as "this file has no URL",
        # which is a real state (an upload that never completed) and must stay distinguishable
        # from "you may not have it". An absent key is the honest third answer, and a client that
        # renders a play button only when a URL is present degrades correctly on its own.
        if (
            media_urls is not None
            and _MEDIA_MARKER in value
            and value.get("uploadedById") not in media_urls
        ):
            for key in _MEDIA_TAKEABLE_KEYS:
                value.pop(key, None)
        for nested in value.values():
            _redact_sensitive(nested, viewer_id, unmasked, media_urls)
    elif isinstance(value, list):
        for item in value:
            _redact_sensitive(item, viewer_id, unmasked, media_urls)
    return value


def public_encode(obj: Any, viewer: Any = None, *, media_urls: Any = _UNSET) -> Any:
    """``jsonable_encoder`` plus a recursive scrub of everything that must not leave the API.

    Three jobs, all performed on the ENCODED structure so none depends on how the row was loaded:

    * password hashes are removed outright, however deeply an embedded User relation is nested
      (``createdBy``/``uploadedBy``/``answeredBy``/``reviewedBy``);
    * ``aadhaarNumber`` and ``pehchanCardNumber`` are masked unless ``viewer`` is entitled to the raw
      value — professor and above, or the researcher who recorded that particular artisan;
    * ``url`` is removed from media nodes unless the caller may take that uploader's files — see
      :data:`_MEDIA_URL_KEYS` for why a URL is a download rather than a description.

    ``viewer`` DEFAULTS TO MASKED, and that default is the point. The mask used to be applied
    per-route inside artisans.py, so it held on the three artisan routes and nowhere else: every
    other response that embedded an Artisan — the questionnaire's interviews, products, tools,
    workshops, media, search — shipped full 12-digit Aadhaar numbers to anyone signed in, at a
    hundred artisans a page. Masking here, defaulting to the safe answer, is what makes the schema's
    "masked on every exported or shared surface" contract hold for includes nobody has written yet:
    a new route leaks nothing until someone deliberately passes a caller who may see more.

    ``media_urls`` follows the same defaulting discipline, and its default is deliberately the
    CHEAPEST SAFE answer rather than the most generous correct one:

    * omitted — derived from ``viewer`` with no database access: every URL for professor-and-above,
      otherwise only the viewer's OWN uploads. A grantee therefore does not see URLs on a route that
      has not thought about it.
    * ``None`` (:data:`ALL_MEDIA_URLS`) — every URL. For an already-gated download surface.
    * a ``set`` of uploader ids — exactly those uploaders' URLs. Routes where a GRANTEE legitimately
      needs the file pass ``await media_url_owners(viewer)``, which adds the granted uploaders at the
      cost of one query.

    Pass the current user from any route whose caller legitimately needs the real number — the
    artisan edit form is the reason that path exists.
    """
    from app.core.deps import get_value, has_rank

    encoded = jsonable_encoder(obj)
    if viewer is None:
        # No viewer named: mask everything and withhold every URL. `set()` rather than None, because
        # None means "all allowed" and this is the path a route reaches by NOT thinking about it.
        allowed: set[str] | None = set() if media_urls is _UNSET else media_urls
        return _redact_sensitive(encoded, viewer_id=None, unmasked=False, media_urls=allowed)

    viewer_id = get_value(viewer, "id")
    if media_urls is _UNSET:
        # RANK ONLY. ``can_download_dataset`` used to be OR-ed in here and in ``media_url_owners``,
        # and it is the one predicate that must not appear in this decision — see that function's
        # docstring for the measurement that killed it.
        if has_rank(viewer, "PROFESSOR"):
            allowed = ALL_MEDIA_URLS
        else:
            allowed = {viewer_id} if viewer_id else set()
    else:
        allowed = media_urls
    return _redact_sensitive(
        encoded,
        viewer_id=viewer_id,
        unmasked=has_rank(viewer, "PROFESSOR"),
        media_urls=allowed,
    )


async def media_url_owners(viewer: Any) -> set[str] | None:
    """Whose media URLs ``viewer`` may be handed: ``None`` for all, else a set of uploader ids.

    ``None`` for professor-and-above. Otherwise the viewer's own uploads plus every uploader who has
    GRANTED them a data-access grant — the same tiered grants ``services/access.owner_download_scope``
    enforces on the export paths, so a grantee who may download a researcher's data can also play
    their recordings, and nobody else can.

    ``can_download_dataset`` IS DELIBERATELY NOT CONSULTED, AND IT USED TO BE. Both this function and
    ``public_encode``'s default read ``has_rank(viewer, "PROFESSOR") or can_download_dataset(viewer)``,
    under a docstring premise — "both may already download the whole repository" — that is FALSE for
    exactly the account it was widening. ``can_download_dataset`` (deps.py) is
    ``has_rank(user, "PROFESSOR") or bool(user.canDownloadDataset)``: a per-user grantable boolean that
    a RESEARCHER can hold without ranking anywhere near Professor. The two download surfaces written
    for that concern say so out loud and decide the opposite for the same account —
    ``data_browser._scope_for`` ("``canDownloadDataset`` is a GRANTABLE boolean … the permission means
    'download the data you can SEE'") and ``export.dataset_manifest``'s ``media_vis`` both narrow with
    ``owned_or_granted_where(owner_field="uploadedById")``. So one boolean bought a below-Professor
    account the ``url``, ``publicUrl`` and ``objectKey`` of EVERY file in the repository through
    ``GET /media`` and ``GET /search``, while ``/data/media/{id}/download`` 404-ed and
    ``/export/dataset`` omitted the same rows from the manifest. Those URLs carry no expiry and no
    auth, so they outlive revocation of the permission, of the grant, and of the account.

    Deleting the term costs the surfaces that legitimately need every URL nothing, because each of
    them says so at its own gate rather than relying on this default: ``datasets.py`` passes
    ``media_urls=ALL_MEDIA_URLS`` explicitly behind ``require_dataset_admin``, and
    ``media.list_orphan_media`` binds an admin. If a future route needs the wide answer, pass
    ``ALL_MEDIA_URLS`` at that route and be visible about it — do not put the boolean back here, where
    it applies to every caller of a function that cannot see which surface it is serving.

    ONE query, and only for the ranks that need it. Pass the result to ``public_encode(media_urls=…)``
    from the routes where a grantee genuinely needs the file — the media list, search, and the
    consolidated questionnaire's audio. Everywhere else the cheap default is correct.

    The grant is read COARSELY, ignoring ``allData``/``scopeItems``: a subset grant names record ids,
    and a media file is not one of the record types a subset can name, so narrowing by it would
    withhold the audio for the very interview that was shared. Erring wide here matches
    ``visibility``'s own precedent for grant-gated reads and is still grant-gated.
    """
    from app.core.db import db
    from app.core.deps import get_value, has_rank

    if has_rank(viewer, "PROFESSOR"):
        return ALL_MEDIA_URLS
    viewer_id = get_value(viewer, "id")
    if not viewer_id:
        return set()
    grants = await db.dataaccessgrant.find_many(
        where={"granteeId": viewer_id, "status": "GRANTED"}
    )
    return {viewer_id, *(grant.ownerId for grant in grants)}


def to_json(value: Any) -> Any:
    """Wrap dict/list values destined for a Prisma Json column. prisma-client-py rejects raw dicts."""
    if isinstance(value, (dict, list)):
        return Json(value)
    return value


def jsonify_metadata(data: dict[str, Any], *fields: str) -> dict[str, Any]:
    """Wrap the given JSON-column fields in ``Json`` if they are plain dict/list values."""
    keys = fields or ("extraMetadata", "measurementAnalysis", "result")
    for key in keys:
        if key in data and isinstance(data[key], (dict, list)):
            data[key] = Json(data[key])
    return data

# Keys a client may deliberately CLEAR on EVERY model that has them.
#
# Update payloads are dumped with ``exclude_unset=True``, so a key is present only when the caller
# actually sent it: ``{"workshopId": None}`` means "unlink this record", which is different from
# omitting the key ("leave it alone"). Stripping those Nones the way we strip every other one made
# unlinking a silent no-op — the save returned 200, the form showed "Unlinked", and the old link
# survived in the database. These keys therefore survive the clean with their explicit ``None``.
#
# THIS SET IS GLOBAL, WHICH IS WHY IT IS SHORT AND WHY IT MUST STAY SHORT. ``clean_data`` is the one
# chokepoint every create and update funnels through and it does not know which model the payload is
# bound for, so a name added here is clearable on all of them. Two things break if that is forgotten:
# ``email`` is NOT NULL on User, DesignerRoster and AccessRoster and nullable on exactly one model,
# so a global entry would turn a typo into a constraint violation on three tables; and CREATE paths
# dump every unset optional as ``None``, so a global entry would send an explicit NULL for every
# field the caller simply did not fill in.
#
# PER-MODEL NULLABLE SCALARS GO THROUGH THE ``clearable`` ARGUMENT INSTEAD — see :func:`clean_data`.
# That is what makes retracting a phone number, an email, an address or a note possible at all; the
# claim that used to stand here, that blanking a scalar is "governed by the field-clearing guard in
# ``deps.assert_can_contribute_fields``", described a governance that could not fire, because the
# ``None`` was already gone by the time the guard ran.
CLEARABLE_KEYS = frozenset(
    {
        "workshopId",
        "craftId",
        "artisanId",
        "productId",
        "toolId",
        "processId",
        "locationId",
        "questionnaireInterviewId",
        # Identity numbers a researcher can legitimately retract: an Aadhaar entered against the
        # wrong artisan has to be removable, and answering "no card" must clear the card number in
        # the same request rather than orphaning it on the record.
        "aadhaarNumber",
        "pehchanCardNumber",
    }
)


# Name-like columns that are title-cased on WRITE (see services/text_format.py for the rule and for
# WHY normalising here rather than in a client is the only fix that holds for web + Android + scripts).
#
# The list is by COLUMN NAME because ``clean_data`` is the one chokepoint every create and update
# funnels through, and it does not know which model the payload is bound for. Every column below is
# name-like in every model that has it:
#
#   name         Artisan.name, Craft.name, Process.name, User.name
#   craftName    Artisan create/update input (resolved to Craft.name), Product.craftName,
#                Tool.craftName  -- casing this BEFORE artisans.resolve_craft_id does its exact-match
#                ``find_unique(where={"name": ...})`` is what stops "bandhani" and "Bandhani" from
#                becoming two crafts
#   artisanName  Product.artisanName, Tool.artisanName
#   productName  ProductDocumentation.productName
#   toolkitName  ToolDocumentation.toolkitName
#   englishName  ToolDocumentation.englishName
#   title        Workshop.title, QuestionnaireSection.title, QuestionnaireInterview.title
#   place        Artisan.place, Craft.place, Workshop.place, Product.place, Tool.place,
#                QuestionnaireInterview.place
#   placeName    Location.placeName (written through ``attach_location``)
#   state        Location.state (written through ``attach_location``). Harmless and idempotent: all
#                36 canonical names in services/address.py are already fixed points of this rule, and
#                the value has been resolved to one of them by LocationInput before it gets here
#   district     Location.district (written through ``attach_location``). Promoted from an
#                extraMetadata key to a real column by 20260727120000_location_stated_address, and
#                this entry is what that promotion was waiting for -- ``attach_location`` funnels
#                the location dict through ``clean_data`` too, so the column normalised from its
#                first write with no new plumbing. Idempotent for the same reason ``state`` is:
#                every canonical district name is already a fixed point of title_case, which
#                tests/test_address_districts.py asserts for all 795 of them
#   village      Location.village (written through ``attach_location``). Free text with no list
#                behind it, so this IS the only normalisation it gets -- "bagru" and "BAGRU" would
#                otherwise be two villages in every group-by
#
# DELIBERATELY ABSENT, because casing them would damage meaning rather than tidy it: notes,
# description, remarks, address, dos, donts, transcriptText/transcriptSummary, caption, prompt, email,
# phone, localName (Indic script, and title_case leaves it alone anyway), and every identifier
# (aadhaarNumber, pehchanCardNumber, originalFilename, objectKey, ids).
TITLE_CASE_FIELDS = frozenset(
    {
        "name",
        "craftName",
        "artisanName",
        "productName",
        "toolkitName",
        "englishName",
        "title",
        "place",
        "placeName",
        "village",
        "district",
        "state",
    }
)


def clean_data(
    data: dict[str, Any],
    *,
    title_case: bool = True,
    clearable: Sequence[str] | frozenset[str] = (),
) -> dict[str, Any]:
    """Drop keys whose value is ``None``, keeping the deliberate nulls in :data:`CLEARABLE_KEYS` plus
    ``clearable``, and title-case the name-like fields in :data:`TITLE_CASE_FIELDS`.

    Casing happens HERE, at the very top of every write path, so the normalised value is what every
    later step sees: the craft lookup that matches on an exact name, the ``RecordRevision`` diff, the
    field-provenance comparison and the uniqueness checks all agree with what is finally stored.

    Pass ``title_case=False`` from a route whose payload happens to reuse one of those column names
    for prose rather than a name — a generated task title, say — where sentence casing is correct.

    ── ``clearable``: THE MODEL'S OWN NULLABLE SCALARS, AND WHY IT IS PER CALL ──────────────────────
    A FIELD THAT CANNOT BE CLEARED IS A 200 THAT DOES NOTHING, which is the worst answer an API can
    give: the form shows the box empty, the save reports success, and the old value is still in the
    database. The case with no workaround at all is retracting personal information a subject has
    asked to have removed — a phone number, an email address, a home address, a note about them —
    because there is no "" to send instead when the column is a nullable ``String?`` and the client
    means NULL.

    It is an argument rather than more names in :data:`CLEARABLE_KEYS` because that set is global and
    this one is not: ``email`` is nullable on one model and NOT NULL on three, so a global entry would
    trade one silent no-op for a constraint violation elsewhere. And because CREATE paths dump every
    unset optional as ``None``, a global entry would also start writing explicit NULLs for boxes the
    researcher merely left blank. An UPDATE route dumping with ``exclude_unset=True`` has neither
    problem: a key is present only because the caller sent it, and the caller sent this model.

    So the rule for a caller is: pass the nullable scalar columns of the model THIS payload updates,
    and pass them only from a route that dumps with ``exclude_unset=True``.
    """
    allowed = CLEARABLE_KEYS | frozenset(clearable) if clearable else CLEARABLE_KEYS
    cleaned = {key: value for key, value in data.items() if value is not None or key in allowed}
    return title_case_fields(cleaned, TITLE_CASE_FIELDS) if title_case else cleaned


def decimal_to_string(data: dict[str, Any]) -> dict[str, Any]:
    converted: dict[str, Any] = {}
    for key, value in data.items():
        if isinstance(value, Decimal):
            converted[key] = str(value)
        elif isinstance(value, dict):
            converted[key] = decimal_to_string(value)
        else:
            converted[key] = value
    return converted


# Where the Android client puts the stated address, and the columns those keys became.
#
# The phone shipped this inside `location.extraMetadata` because at the time there were no columns
# to put it in — its own comment says so. Migration 20260727120000_location_stated_address then
# promoted all four to real columns and `require_location` began demanding `district`, which no
# Android build sends: not the one about to ship, and — the part that matters — not the one already
# installed on every phone in the field. Deploying the strict rule alone would 422 every create from
# every device until an APK reached it, and a device that is offline in a workshop cannot be reached.
#
# So the server accepts both shapes and normalises on the way in. This is not a temporary shim to be
# removed once the fleet updates: records created by today's phones will carry the metadata form for
# as long as they exist, and an edit of one of those rows re-sends what it was given.
_STATED_ADDRESS_FROM_META: tuple[tuple[str, tuple[str, ...]], ...] = (
    ("district", ("district",)),
    ("village", ("village",)),
    ("subjectLatitude", ("subjectLatitude", "artisanLatitude")),
    ("subjectLongitude", ("subjectLongitude", "artisanLongitude")),
)


def lift_stated_address(location_data: dict[str, Any]) -> dict[str, Any]:
    """Copy a stated address out of ``extraMetadata`` into the columns that now hold it.

    A value already present as a column always wins — a client that sends both means the column.
    The metadata keys are left in place rather than popped: they are what older builds read back,
    and removing them would blank the field on a phone that has not updated yet.
    """
    meta = location_data.get("extraMetadata")
    if not isinstance(meta, dict):
        return location_data
    for column, keys in _STATED_ADDRESS_FROM_META:
        if location_data.get(column) not in (None, ""):
            continue
        for key in keys:
            value = meta.get(key)
            if value not in (None, ""):
                location_data[column] = value
                break
    return location_data


async def attach_location(data: dict[str, Any]) -> dict[str, Any]:
    location = data.pop("location", None)
    if location:
        location_data = location.model_dump() if hasattr(location, "model_dump") else dict(location)
        location_data = lift_stated_address(location_data)
        # `extraMetadata` is a Prisma Json column and prisma-client-py rejects a raw dict, so any
        # request carrying one was a 500 rather than a save — which is every Android create, since
        # that is where the phone keeps the stated address.
        created = await db.location.create(data=jsonify_metadata(clean_data(location_data)))
        data["locationId"] = created.id
    return data


# Characters Postgres will not accept inside a text value. NUL is the one that matters: a `text`
# column cannot hold 0x00 at all, so the driver raises and — because this is a query PARAMETER, not
# a query the caller composed — the failure surfaces as a 500 rather than as a validation error.
# The rest are the C0 controls that carry no meaning in a search box and only exist in pasted junk.
_UNSEARCHABLE = {c: None for c in range(32) if c not in (9, 10, 13)}


#: The LIKE metacharacters, and the escape that neutralises them. **THE BACKSLASH MUST BE FIRST.**
#: Escaping ``%`` before ``\`` would turn a typed backslash into an escape for the escape and change
#: what the next character means — the classic ordering bug in every escaping routine ever written.
_LIKE_METACHARACTERS = ("\\", "%", "_")


def contains(value: str) -> dict[str, Any]:
    """A case-insensitive `contains` filter: control bytes stripped, LIKE metacharacters escaped.

    Every text search in the app funnels through here — 89 call sites across ``app/api/routes`` and
    ``app/services``, counted 2026-08-22 — which is why both of the treatments below live here rather
    than in each route.

    THAT SENTENCE WAS AN ASPIRATION FOR A WHILE, AND IS NOW ENFORCED. Five search boxes composed
    ``{"contains": term, "mode": "insensitive"}`` by hand and got neither treatment: the access
    roster, the designer roster, the designer directory, the questionnaire-forms list, and the
    design-workshop REF picker (``services/design_workshops.reference_options``, searching
    ``spec.search_fields``) — a picker, not a list endpoint; ``api/routes/design_workshops.py``
    never had the defect. Counting the funnel's call sites could never have found them, because a
    route that bypasses the funnel does not appear in the count — which is why the repair is not five
    substitutions but ``test_record_filters.test_no_route_still_hand_rolls_a_contains_filter``, a
    sweep that fails on the sixth one somebody writes.

    ── THE BYTES POSTGRES CANNOT STORE ─────────────────────────────────────────────────────────────
    A single NUL byte pasted into any search box — /search, artisans, crafts, tools, products, media,
    processes, questionnaires, users — returned a 500 from every one of them. Stripping is the right
    response rather than rejecting: a researcher who pasted a name out of a PDF and picked up a stray
    control character wants their search to run, not a validation error about a byte they cannot see.

    Tab, newline and carriage return are deliberately kept — Postgres stores them happily and they
    can legitimately appear in a pasted multi-line name.

    ── AND THE PATTERN SYNTAX, WHICH LEAKED FOR THE LIFE OF EVERY SEARCH BOX ────────────────────────
    Prisma's ``contains`` compiles to ``ILIKE '%' || term || '%'`` and the term was interpolated
    unescaped, so ``%`` and ``_`` were HONOURED as wildcards rather than matched as characters.
    Measured live against this database, admin token, before the escape was added:

    ==========================================  ======  ================================
    request                                     rows    correct answer
    ==========================================  ======  ================================
    ``eligible-viewers?search=zzzznomatch``     0       0
    ``eligible-viewers?search=_``               2000    0 — no name or email holds one
    ``eligible-viewers?search=%``               2000    0
    ``eligible-viewers?search=_designer``       635     0 — ``_`` matched any character
    ``artisans?search=_``                       731     every artisan
    ``artisans?search=%``                       731     every artisan
    ==========================================  ======  ================================

    **THIS WAS NEVER SQL INJECTION.** Prisma parameterises and the values arrive bound; what leaked
    was pattern syntax, not SQL. What it cost was the opposite of what a search box is for: an admin
    pasting a colleague's full address — ``first_last@org`` — to narrow a list they had just been
    told was truncated got a WIDER result than they typed, because ``_`` matched any character.

    THE ESCAPE IS HONOURED THROUGH A BOUND PARAMETER, and that was checked rather than assumed —
    Postgres' default LIKE escape is the backslash and no ``ESCAPE`` clause is needed. Measured on
    this database:

    ==========  ===================================================================
    pattern     matches
    ==========  ===================================================================
    ``_``       ``first_last@org``, ``firstXlast@org``, ``plain@org``, ``100% …``
    ``\\_``      ``first_last@org`` and nothing else
    ``%``       everything
    ``\\%``      ``100% cotton`` and nothing else
    ``\\\\``     ``back\\slash`` and nothing else
    ==========  ===================================================================

    THE SIBLING BELOW DELIBERATELY DOES NOT DO THIS. :func:`plain` compares EQUAL, and an ``=``
    comparison has no pattern syntax in it — escaping there would stop ``?state=A_P`` from matching
    the row that literally is ``A_P``, turning a fix into a new defect.
    """
    escaped = value.translate(_UNSEARCHABLE)
    for character in _LIKE_METACHARACTERS:
        escaped = escaped.replace(character, "\\" + character)
    return {"contains": escaped, "mode": "insensitive"}


def prose_contains(field: str, value: str) -> dict[str, Any]:
    """:func:`contains` for a column that may now hold a FORMATTED document rather than a paragraph.

    ── WHY A SEARCH HELPER EXISTS AT ALL ───────────────────────────────────────────────────────────
    The larger free-text record columns (artisan notes, product remarks and materials, tool remarks
    and usage, process notes) accept rich text from this release on, and they are still ``String?``
    columns — the storage decision was explicitly "no migration, no new column". A row somebody
    formatted therefore holds ``{"blocks":[…]}`` where prose used to be, and the ``ILIKE '%term%'``
    this composes is reading that JSON directly. Read the banner above
    ``rich_text.stored_text_document`` before changing either side.

    The words themselves survive: they sit inside ``"text"`` values as themselves, so an ordinary
    one-word search still finds them and this returns EXACTLY the single-clause filter the call site
    had before. What does not survive is any term carrying a character JSON escapes — a quote, a
    backslash, a newline, a tab — because the column holds the escaped form. ``he said "no"`` would
    match nothing at all and the researcher would be told the record does not exist. The second
    needle is that repair, and it is only ever added for terms that need it.

    ── THE LIMIT, STATED RATHER THAN HIDDEN ────────────────────────────────────────────────────────
    Two gaps remain on FORMATTED rows and no ``contains`` can close either: a phrase interrupted by
    a bolded word is split across two spans, and a phrase spanning a paragraph or list-item boundary
    is split across two blocks — neither can match. Going further needs a generated column or a real
    text index, i.e. the migration this feature deliberately did not take. The blast radius is
    bounded by the encoder's rule that an UNFORMATTED value is stored as plain prose: rows nobody
    formatted — which is nearly all of them — search exactly as they always have.
    """
    needles = search_needles(value)
    if len(needles) == 1:
        return {field: contains(needles[0])}
    return {"OR": [{field: contains(needle)} for needle in needles]}


def plain(value: str) -> str:
    """The same sanitising as :func:`contains`, for a filter that compares EQUAL rather than LIKE.

    ``contains`` covered the search boxes and left every ``where["state"] = state`` and
    ``where["craftId"] = craftId`` beside them unguarded, so ``?state=%00`` and ``?craftId=%00``
    still reached Postgres and still came back as a bare 500 with a ``DataError`` in the log —
    the same failure, one line away from the fix. Every such request is a logged server error
    with a stack trace, so an authenticated scan can fill the error log at will, and the web
    renders any 5xx from a list endpoint as "you are offline" (lib/offline.ts) — so the operator
    is shown a connectivity story about a malformed input.

    Stripping rather than rejecting, for the reason ``contains`` gives: it is the same byte, the
    same paste and the same person, and answering two different ways depending on which box they
    put it in would be the arbitrary choice.
    """
    return value.translate(_UNSEARCHABLE)


#: ``RecordStatus``, mirrored from prisma/schema.prisma. Kept as a frozenset here rather than
#: imported from the generated client so a route can validate without the client being generated —
#: the same reasoning as DESIGN_WORKSHOP_STATUSES in schemas/design_workshops.py.
RECORD_STATUSES = frozenset({"DRAFT", "PENDING", "APPROVED", "REJECTED", "NEEDS_REVISION"})
#: ``MediaType``, mirrored the same way.
MEDIA_TYPES = frozenset({"IMAGE", "VIDEO", "AUDIO", "PDF", "DOCUMENT", "OTHER"})


def enum_filter_or_422(value: str, allowed: frozenset[str], *, field: str = "status") -> str:
    """One filter value, checked against the enum column it is about to be compared to.

    A VALUE THE ENUM DOES NOT HAVE IS A 500, NOT AN EMPTY LIST. Prisma refuses to build the query
    and the route answers ``{"error": "FieldNotFoundError"}`` with a stack trace in the log — for
    a lowercase "draft", for an "ALL" sent by a client whose dropdown labels its empty option, for
    a stale bookmarked URL, for a filter value from a build where the enum was spelled
    differently. Nine list endpoints did this while ``tasks.assert_status_value`` and
    ``workshops._status_or_422`` had already answered it correctly twice in the same file family,
    so the fix existed in-repo and had simply not been applied to the rest.

    The 422 NAMES THE ALLOWED VALUES, which is the part a client can act on: "status must be one
    of APPROVED, DRAFT, …" tells a developer their casing is wrong, where a 500 tells them the
    server is broken.
    """
    if value not in allowed:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail=f"{field} must be one of {', '.join(sorted(allowed))}",
        )
    return value


# =================================================================================================
# WHO MAY SEE WHAT — two different questions, two different filters
#
# READING the repository and TAKING data out of it are not the same act, and until now one filter
# answered both. That filter said "below Professor you see only your own rows plus rows whose owner
# granted you access", and because every list route used it, it governed READING too. The result was
# a repository that hid itself from the people filling it: a researcher's dashboard counted only
# their own uploads, /search returned only their own uploads, the map drew only their own uploads,
# and an account that had uploaded nothing yet opened an app that appeared to contain nothing at all.
# For a shared documentation corpus that is precisely backwards — the whole point of pooling the
# fieldwork is that everyone can see the pool.
#
# So the two questions are now asked separately:
#
#   viewable_where            — MAY THIS ACCOUNT LOOK AT THIS ROW?  Yes, for every signed-in
#                               account. Reading is open across the repository.
#   owned_or_granted_where    — MAY THIS ACCOUNT TAKE THIS ROW AWAY? Only their own rows, plus rows
#                               whose owner granted them access. Unchanged, and still what every
#                               download / export / bulk-manifest path uses.
#
# Nothing about DOWNLOADING, COMMENTING or EDITING moved. Those are decided by
# ``services/access.py`` (the tiered grants), ``core/deps.py`` (the rank ladder) and the two
# ``owned_or_granted_where`` surfaces below, exactly as before. And nothing about PII moved either:
# ``public_encode`` masks Aadhaar and Pehchan numbers for anybody who is not the artisan's own
# recorder or a Professor+, and it masks by DEFAULT — so widening who may read a row does not widen
# who may read the regulated numbers on it.
#
# The old name ``visibility_where`` is deliberately GONE rather than redefined. It had seventeen call
# sites and the two policies now differ, so a call site that kept compiling against the old name
# would have silently picked whichever policy the rename happened to land on. Removing it forced
# every one of them to be visited and classified.
# =================================================================================================


async def viewable_where(user: Any, owner_field: str = "createdById") -> dict[str, Any]:
    """Row filter for READING the repository: everything, for every signed-in account.

    Authentication is the gate — ``Depends(get_current_user)`` on the route — and past it there is no
    per-row narrowing, so this returns an empty ``where``. ``owner_field`` is accepted and ignored so
    that the read sites and the download sites are spelled the same way and can be told apart by
    NAME rather than by argument.

    WHY IT IS STILL A FUNCTION, AND STILL AWAITED. It is the one hook where the read policy lives. A
    future rule — "records pending review are hidden from volunteers", say — belongs here, applied to
    every list route at once, rather than being re-derived in twenty of them. Keeping the call sites
    is what makes that a one-line change instead of an audit. It stays ``async`` because every caller
    already awaits it, several inside ``gather_reads`` alongside real reads.

    It must still be AND-composed the way it always was (nest it under ``where["AND"]``): it is empty
    today, and a route that stopped composing it correctly would break the day it stops being empty.
    """
    return {}


async def owned_or_granted_where(user: Any, owner_field: str = "createdById") -> dict[str, Any]:
    """Row filter for TAKING DATA OUT — downloads, exports, and bulk manifests.

    Professor and above (and admins) may take every row — an empty filter. Below professor the answer
    is the rows they own, plus rows owned by anyone who has GRANTED them a data-access grant (any
    tier, subset grants included — coarse, but always grant-gated). ``owner_field`` names the row's
    owner column (``createdById`` for records, ``uploadedById`` for media). It must be AND-composed
    with any other ``OR`` the query builds (nest it under ``where["AND"]``) so a search ``OR`` never
    overwrites it.

    This is the ORIGINAL ``visibility_where`` body, unchanged, because the download policy is
    unchanged. Only its reach shrank: it now rides the export and /data queries and nothing else.

    THE GRANT TEST IS PART OF THE PAGE QUERY, not a query of its own. Reading the grant table first
    and folding the owner ids into an ``IN`` list cost a full round trip BEFORE the page could even
    be asked for. It also got worse with success: the ``IN`` list is every owner who has ever granted
    the caller anything, shipped as query parameters on every request. Expressed as a relation
    filter, Postgres answers the same question inside the query it was already running, against the
    ``granteeId`` index that exists for exactly this.

    A THIRD CLAUSE, ON MEDIA ONLY: THE RECORDINGS OF A DESIGN WORKSHOP THIS ACCOUNT MAY OPEN.
    A ``DesignWorkshopViewer`` row is the mechanism by which two designers run one workshop, and it
    is neither of the two clauses above — the co-designer did not upload the clip and holds no
    ``DataAccessGrant`` from whoever did. So the transcript preview, whose whole stated purpose is
    that "a designer about to append transcripts to a report submitted to a ministry needs to see
    what they are appending", answered a granted co-designer with ``{"items": [], "total": 0}``
    over six interviews sitting in the database — an empty list that reads as "nothing exists" when
    it means "withheld from you", landing on the exact feature the grant was built for. The
    generator then told them "6 recording(s) could not be included", so the two screens contradict
    each other and the preview is the one that lies. The CREATOR was refused the same way whenever
    a colleague did the uploading, which is the ordinary division of labour on a field team.

    Granting it is the coherent answer and not a widening of the boundary: the same account can
    already open every stage of that workshop through ``load_workshop_or_404``, edit the stage that
    names the clip, and read stage 8's ``surveyResponse``, which carries the same testimony as text.
    Withholding only the audio protects nothing and costs the feature.

    THE LINK IS A TAG, NOT A FOREIGN KEY, and that is why this clause costs a query the other two
    do not. ``MediaFile`` has no column pointing at ``DesignWorkshop``; both clients file every
    design-workshop upload under ``linkedRecordType="designWorkshop"`` with the workshop id in
    ``linkedRecordId`` (see ``dictation_consent.MEDIA_TAG``, which reached the same conclusion for
    the consent gate). There is no relation for Postgres to walk, so the ids have to be read first.
    The cost is bounded by how many workshops one person is on — a handful, indexed by
    ``DesignWorkshopViewer(userId)`` and ``DesignWorkshop(createdById)`` — and it is paid only
    below professor and only on the media variant, which is to say on the transcript, annexure and
    export paths, never on the record queries.
    """
    from app.core.deps import get_value, has_rank

    if has_rank(user, "PROFESSOR"):
        return {}
    uid = get_value(user, "id")
    # ``createdById`` -> ``createdBy``, ``uploadedById`` -> ``uploadedBy``: the owner COLUMN and the
    # owner RELATION are named that way on every model this filter is applied to.
    owner_relation = owner_field.removesuffix("Id")
    branches: list[dict[str, Any]] = [
        {owner_field: uid},
        {
            owner_relation: {
                "is": {"dataAccessAsOwner": {"some": {"granteeId": uid, "status": "GRANTED"}}}
            }
        },
    ]
    # ONE VARIANT OF THIS FUNCTION IS A QUERY, THE OTHER IS NOT, AND THAT ASYMMETRY IS THE POINT.
    # Everything above is dictionary work; this line makes the MEDIA variant read the workshop table
    # (see the helper for why the tag pair leaves no alternative). Two consequences a caller has to
    # know about, both learned the hard way when this arm landed:
    #
    #   * ``owner_field="uploadedById"`` may only be awaited where the Prisma client is connected.
    #     ``tests/test_record_filters.py`` had called this builder with no client since the day it was
    #     written — it was a pure predicate then — and started raising ``ClientNotConnectedError``
    #     from inside a filter builder, which is a baffling place to be handed a database error. That
    #     test now doubles ``_design_workshop_media_branches`` and asserts the arm is still here; if
    #     you move this line, move the seam it stubs with it.
    #   * the record variant must NOT grow a lookup to match. It rides every /export CSV and every
    #     /data page for every account below professor, where a per-request round trip buys nothing:
    #     ``MediaFile`` is the only model in this repository whose rows a design workshop reaches
    #     without an owner column or a grant.
    if owner_field == "uploadedById":
        branches.extend(await _design_workshop_media_branches(uid))
    return {"OR": branches}


async def _design_workshop_media_branches(user_id: str) -> list[dict[str, Any]]:
    """The "recordings of a workshop I may open" arm of the media filter, or nothing.

    Split out of :func:`owned_or_granted_where` so the tag lookup can be read — and skipped — on
    its own. Returns a LIST rather than a clause because an account on no design workshop
    contributes no branch at all: an ``IN []`` arm would be a permanently false predicate shipped
    on every export query for every researcher in the repository.

    ``visible_to_clause`` is the same expression ``GET /design-workshops`` scopes its list with and
    ``load_workshop_or_404`` admits on, imported rather than re-spelled: this predicate must mean
    exactly "a workshop this account may open", and the day that widens again (it already widened
    once, from creator-only) the audio must widen with it rather than needing a second edit
    somebody has to notice. Imported inside the function because
    ``services/design_workshop_viewers`` imports this module.

    Failure here is NOT swallowed. This runs inside read predicates whose callers treat an empty
    filter as "no narrowing", so returning [] on an error would be indistinguishable from "this
    account is on no workshop" — a wrong, silent refusal on exactly the surface the grant exists
    to serve. Let it raise; a 500 is recoverable and a lie is not.
    """
    from app.services.design_workshop_viewers import visible_to_clause
    from app.services.dictation_consent import MEDIA_TAG

    if not user_id:
        return []
    # ``deletedAt: None`` because a soft-deleted workshop is a 404 for everyone but an admin, and an
    # admin is a professor-and-above who never reaches this branch. Leaving it out would let a
    # grant on a deleted workshop keep handing over its recordings after the record itself is gone.
    rows = await db.designworkshop.find_many(
        where={"deletedAt": None, **visible_to_clause(user_id)}
    )
    ids = [row.id for row in rows]
    if not ids:
        return []
    return [{"linkedRecordType": MEDIA_TAG, "linkedRecordId": {"in": ids}}]


async def own_rows_where(user: Any, owner_field: str = "createdById") -> dict[str, Any]:
    """Row filter for "MINE" — strictly the rows this account owns, whatever its rank.

    Distinct from both filters above and needed by neither of their call sites: it is what a
    "you have contributed N records" figure means. That figure used to fall out of the old
    ``visibility_where`` by accident, because below Professor the read filter WAS an ownership filter
    — which is also why the same figure was wrong for a professor, who saw the repository total
    labelled as their own work. Now that reading is open, "mine" has to be asked for explicitly, and
    this is where it is asked.
    """
    from app.core.deps import get_value

    return {owner_field: get_value(user, "id")}


def apply_status_policy_create(user: Any, data: dict[str, Any]) -> dict[str, Any]:
    """Force the initial status by rank. Professor and above default to APPROVED (keeping any explicit
    status they passed); everyone below is FORCED to PENDING no matter what the client sent, so a
    researcher / field contributor / volunteer can never self-approve on create. Mutates ``data``.

    One thing outranks this: a submission made after its workshop ended. Routes that accept a
    ``workshopId`` call ``workshop_access.pin_pending_if_late`` immediately AFTER this, which pins such
    a record to PENDING even for a professor+ — only an admin may approve a late entry."""
    from app.core.deps import has_rank

    if has_rank(user, "PROFESSOR"):
        data.setdefault("status", "APPROVED")
    else:
        data["status"] = "PENDING"
    return data


async def apply_status_policy_update(user: Any, record: Any, data: dict[str, Any]) -> dict[str, Any]:
    """Authorize a status change on update, else silently drop it — old clients always echo the current
    status, so an unauthorized change must never 403. A status change sticks only when the editor is
    Professor+ AND is either the record's creator or ranks high enough to review the creator's work
    (``can_review_record``). Everything else — including a no-op that merely re-sends the current
    status — pops ``status`` so the stored value is untouched and ``resubmit_status`` can still flip a
    creator's edit back to PENDING. Call right after ``guard_record_edit`` and before ``resubmit_status``
    (on the workshop-aware routes, ``workshop_access.stamp_workshop_submission`` and
    ``pin_pending_if_late`` sit in between — the pin must run after this so a record flagged as a late
    workshop submission stays PENDING regardless of what this policy would have allowed).
    Mutates and returns ``data``; a no-op for records with no status column (``status`` never present)."""
    from app.core.deps import can_review_record, enum_or_raw, get_value, has_rank

    if "status" not in data:
        return data
    new_status = str(enum_or_raw(data["status"]))
    current = str(enum_or_raw(get_value(record, "status")))
    if new_status != current and has_rank(user, "PROFESSOR"):
        creator_id = get_value(record, "createdById")
        if creator_id is not None and creator_id == get_value(user, "id"):
            return data
        creator = await db.user.find_unique(where={"id": creator_id}) if creator_id else None
        if can_review_record(user, get_value(creator, "role") if creator else None):
            return data
    data.pop("status", None)
    return data


def add_date_range(where: dict[str, Any], field: str, date_from: datetime | None, date_to: datetime | None) -> None:
    range_filter: dict[str, Any] = {}
    if date_from:
        range_filter["gte"] = date_from
    if date_to:
        range_filter["lte"] = date_to
    if range_filter:
        where[field] = range_filter


async def require_record(delegate: Any, record_id: str) -> Any:
    record = await delegate.find_unique(where={"id": record_id})
    if not record:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Record not found")
    return record


# --- Loading a page's relations without paying for them one at a time ----------------------------
#
# THE PROBLEM THIS SOLVES, measured rather than guessed. Prisma's query engine already batches a
# relation by ROW — ``include={"craft": True}`` on twenty artisans issues one
# ``SELECT … FROM "Craft" WHERE id IN ($1,…)``, not twenty selects — so there is no classic N+1 here.
# What it does NOT do is issue those relation queries CONCURRENTLY: each ``include`` costs its own
# round trip, one after the next, and the page waits for all of them in series.
#
# That is free on a database next door and ruinous on this deployment, where PostgreSQL lives in a
# different AWS region from the web box: ``/health/ready`` measures a single ``SELECT 1`` at 683ms.
# Counted through a proxy in front of a local database, ``GET /tools?pageSize=20`` issued SEVEN
# sequential round trips (count, the page, then Craft, Location, MediaFile, User, ToolArtisan) — and
# in production it answered in 4.8 seconds for twenty rows out of seventy-four. The number of ROWS
# never entered into it; the number of RELATIONS did.
#
# So relations are loaded here instead: one batched query per relation, all issued together, and the
# results grafted back onto the rows. A page then costs a fixed THREE waits — the page and its count
# together, then every relation at once — no matter how many relations are declared or how many rows
# come back. The same shape holds at a hundred times the data, and it needs no cache, no broker and
# no second process to do it, so the 1 GiB box gets the same win as a large one.
#
# The rows come back as ordinary Prisma model instances with their relation attributes populated,
# exactly as ``include`` would have left them, so ``public_encode`` and every caller downstream are
# untouched — including the per-artisan Aadhaar decision, which still sees one node per artisan.


class Relation(NamedTuple):
    """One relation to load alongside a page of rows.

    ``field`` is the attribute set on each row (and therefore the key in the JSON response).
    ``model`` names the Prisma delegate on ``db`` — ``"craft"``, ``"mediafile"``, ``"user"``.

    ``key`` means different things by direction, because the foreign key lives on a different side:

    * to-one (``many=False``): the column ON THE PARENT holding the child's id (``"craftId"``);
    * to-many (``many=True``): the column ON THE CHILD holding the parent's id (``"toolId"``).

    ``include`` is passed straight through for a nested level (a tool's ``artisanLinks`` reaching its
    ``artisan``). Nesting still costs one round trip per level, but those levels run INSIDE the
    parallel wave rather than after everything else in it.
    """

    field: str
    model: str
    key: str
    many: bool = False
    include: dict[str, Any] | None = None


def include_of(relations: Sequence[Relation]) -> dict[str, Any]:
    """The equivalent Prisma ``include`` argument for the same relations.

    Write paths still hand ``include=`` to ``create``/``update``, where one extra round trip is
    noise next to the write itself and a single statement is the safer thing. Deriving that argument
    from the same tuple the read paths use is what stops the two descriptions of "what a tool looks
    like on the wire" from drifting apart — a relation added for the list would otherwise quietly go
    missing from the response to the PATCH that created it.
    """
    return {
        rel.field: ({"include": rel.include} if rel.include else True) for rel in relations
    }


async def hydrate_relations(rows: Sequence[Any], relations: Sequence[Relation]) -> None:
    """Populate ``relations`` on ``rows`` in one parallel wave of batched queries. Mutates the rows.

    Every row ends up with every declared attribute set — ``None`` for an unmatched to-one, ``[]``
    for an empty to-many — which is what ``include`` produces, so an absent relation stays
    distinguishable from a relation that is genuinely empty.
    """
    if not rows or not relations:
        return

    planned: list[tuple[Relation, Any]] = []
    for rel in relations:
        if rel.many:
            ids = {row.id for row in rows}
        else:
            ids = {getattr(row, rel.key, None) for row in rows} - {None}
        if not ids:
            # Nothing to look up. Prisma skips the query in this case too; setting the attributes
            # here keeps the row shape identical to the include it replaces.
            for row in rows:
                setattr(row, rel.field, [] if rel.many else None)
            continue
        args: dict[str, Any] = {"where": {(rel.key if rel.many else "id"): {"in": sorted(ids)}}}
        if rel.include:
            args["include"] = rel.include
        planned.append((rel, getattr(db, rel.model).find_many(**args)))

    if not planned:
        return
    fetched = await gather_reads(*(coro for _, coro in planned))

    for (rel, _), children in zip(planned, fetched):
        if rel.many:
            grouped: dict[Any, list[Any]] = {}
            for child in children:
                grouped.setdefault(getattr(child, rel.key, None), []).append(child)
            for row in rows:
                setattr(row, rel.field, grouped.get(row.id, []))
        else:
            by_id = {child.id: child for child in children}
            for row in rows:
                setattr(row, rel.field, by_id.get(getattr(row, rel.key, None)))


def with_id_tiebreak(order: Any) -> list[dict[str, Any]]:
    """The caller's ordering, made TOTAL by appending ``id`` — the one column that is unique.

    **OFFSET PAGING OVER A NON-TOTAL ORDER MISSES ROWS AND REPEATS OTHERS, AND BOTH ARE SILENT.**
    ``LIMIT/OFFSET`` re-runs the whole sort for every page, and Postgres is free to break a tie
    differently each time; a row that changes side of the cut between the request for page 1 and the
    request for page 2 is either handed over twice or never handed over at all. The list looks
    complete either way — it has the right number of rows and no gap in it — so the only way anybody
    finds out is by hunting for a record that is definitely there and not finding it.

    THE TIES HERE ARE NOT HYPOTHETICAL. ``createdAt`` is what almost every list in this API sorts by,
    it has no unique index, and the access-roster migration inserted every grandfathered row with one
    ``CURRENT_TIMESTAMP`` — four hundred people sharing a single sort key. ``name`` is worse: the
    picker note in ``tasks.py`` counts 204 accounts called "Sync Test". And even without duplicate
    keys, an attempt-count bump or any other update churns heap order between two page requests.

    Two modules already fix this by hand and say why — ``feedback.list_feedback`` ("``id`` is the
    TIEBREAKER and it is load-bearing now that this read is paged") and
    ``design_workshops`` DISTINCT ON ("the ``createdAt, id`` tiebreak keeps the answer STABLE"). This
    is the same fix applied at the chokepoint, so a list route added next season inherits it.

    The direction follows the LAST clause of the caller's order, so a newest-first list stays
    newest-first within a tie group and an A-Z list stays A-Z. A caller that already names ``id`` is
    returned unchanged — appending a second ``id`` clause is at best noise and at worst an error from
    the query builder.
    """
    clauses = [dict(clause) for clause in (order if isinstance(order, list) else [order])]
    if any("id" in clause for clause in clauses):
        return clauses
    last = clauses[-1] if clauses else {}
    direction = next(iter(last.values()), "asc") if last else "asc"
    return [*clauses, {"id": direction if direction in ("asc", "desc") else "asc"}]


async def count_and_page(
    delegate: Any,
    *,
    where: dict[str, Any],
    skip: int,
    take: int,
    order: Any,
    relations: Sequence[Relation] = (),
) -> tuple[int, list[Any]]:
    """The ``(total, items)`` a paged list route needs, in two waits instead of two-plus-N.

    The count and the page do not depend on each other, so they go together; the relations depend
    only on which rows came back, so they go together after. Callers unpack the pair exactly as they
    would have read it in sequence.

    The ordering is made total on the way through — see :func:`with_id_tiebreak`. Doing it here and
    not in each route is the point: every list that pages through this helper is stable by
    construction, including the ones nobody has written yet.
    """
    total, items = await gather_reads(
        delegate.count(where=where),
        delegate.find_many(where=where, skip=skip, take=take, order=with_id_tiebreak(order)),
    )
    await hydrate_relations(items, relations)
    return total, items


# Fields that are infrastructural / system-managed and should not be attributed to a contributor.
PROVENANCE_SKIP_FIELDS = {
    "extraMetadata",
    "location",
    "locationId",
    "createdById",
    "createdAt",
    "updatedAt",
    "reviewedById",
    "reviewNotes",
    "reviewedAt",
    "recordedAt",
    "recordedTimezone",
    "measurementAnalysis",
    "measurementAnalysisStatus",
    "measurementImageId",
    # The per-dimension method markers a client sends alongside the numbers. Skipped for the same
    # reason as the three measurement keys above it, and then some: it is not a field anybody filled
    # in, it is a hint about HOW they filled in three other fields. Attributed as a field of its own
    # it would put a designer's name against a dictionary they never saw. It is also popped off
    # ``new_data`` by :func:`merge_field_provenance` before the loop that reads this set — the entry
    # is belt and braces, so a future caller that merges provenance without popping still cannot
    # attribute it.
    MARKER_BODY_KEY,
}

#: The ``extraMetadata`` keys :func:`merge_field_provenance` must NOT carry forward off the stored
#: row. Everything else on that column survives an edit; see the banner in that function for why
#: these two are the exceptions — one has a single writer that runs first, the other is this
#: function's own output.
_EXTRA_NOT_CARRIED = frozenset({"workshopSubmission", "fieldProvenance"})


def merge_field_provenance(new_data: dict[str, Any], user: Any, previous: Any | None = None) -> None:
    """Record which user populated/changed each field, stored under extraMetadata.fieldProvenance.

    On create (``previous`` is ``None``) every non-empty field is attributed to ``user``. On update
    only fields whose value actually changes are re-attributed; unchanged fields keep the original
    contributor carried over from the previous record. This mutates ``new_data`` in place.

    **A DIMENSION ALSO RECORDS HOW IT WAS MEASURED, AND THAT IS WHY THIS FUNCTION STOPPED LYING.**
    ``lengthInches`` / ``breadthInches`` / ``heightInches`` are printed as documented dimensions and
    read by somebody costing a production run, and three different processes write them: a tape
    reading somebody typed, ``photoMeasure``'s arithmetic, and a vision model's estimate off a
    grid-sheet photograph. Until the marker below existed this function stamped all three with the
    ``{by, byName, at}`` of whoever pressed Save — so a model's guess that had auto-filled the form
    was stored asserting that a named human had measured it. The method now sits BESIDE the name
    rather than replacing it, because the true sentence is *a vision model estimated this, and
    R. Menon accepted it into the record at that moment*, and stripping the name would delete the most
    useful fact on the row. ``services/measurement_provenance`` holds the whole argument.

    ── THE REST OF ``extraMetadata`` SURVIVES THE EDIT, AND FOR A LONG TIME IT DID NOT ──────────────
    This function OWNS the ``extraMetadata`` value that reaches Prisma: it rebuilds the column and
    assigns it. It used to rebuild it from the REQUEST BODY alone, lifting nothing but
    ``fieldProvenance`` off the stored row — so a PATCH of one phone number wrote back an
    ``extraMetadata`` containing that provenance blob and nothing else, and every other key the row
    was carrying was gone. Those keys are not decoration: ``design_workshops.REFERENCE_MODELS``'s
    Artisan ``data`` lambda reads the legacy ``specialisation`` / ``experienceYears`` / ``age``
    spellings off ``extraMetadata`` as the fallback that fills the report's participant table, and
    its own comment says that read "must not be deleted" because it is the only remaining record for
    the artisans the column migration deliberately refused to guess at ("30+", "about 30"). Editing
    a phone number deleted it.

    And silently is exact: ``extraMetadata`` is the FIRST entry in ``access.REVISION_SKIP_FIELDS``,
    so no ``RecordRevision`` recorded the loss, there was nothing to undo it from, and the response
    looked like a normal save.

    The seed below is the fix. Two keys are deliberately NOT carried across from the stored row:

    * ``workshopSubmission`` — SERVER-OWNED, and ``workshop_access.stamp_workshop_submission`` is its
      single writer. It runs immediately before this function and has already decided the
      authoritative value (a fresh check, the stamp carried off the record, or deliberately nothing
      at all when a record is unlinked). Seeding it here as well would resurrect the stamp the single
      writer had just chosen to drop, which is how a late submission stops needing admin approval.
    * ``fieldProvenance`` — read separately, from the stored row, into ``provenance`` below. It is
      dropped from BOTH sides, the stored seed and the incoming body, because a client that sends its
      own ``fieldProvenance`` is asserting who filled in each field; that is this function's answer
      to give, not the caller's.
    """
    from app.core.deps import get_value, is_empty_value, values_match

    previous_extra = get_value(previous, "extraMetadata") if previous is not None else None
    if not isinstance(previous_extra, dict):
        previous_extra = {}
    incoming_extra = new_data.get("extraMetadata")
    incoming_extra = incoming_extra if isinstance(incoming_extra, dict) else {}

    base_extra: dict[str, Any] = {
        key: value for key, value in previous_extra.items() if key not in _EXTRA_NOT_CARRIED
    }
    base_extra.update(
        {key: value for key, value in incoming_extra.items() if key != "fieldProvenance"}
    )

    # POPPED, NOT READ. ``measurementMethods`` is not a column on either documentation table, and
    # ``clean_data`` only drops keys whose value is None — so a marker that is actually sent survives
    # all the way into ``db.productdocumentation.create(data=data)`` and is a 500 on the save, not a
    # validation error the researcher could act on. This pop is the only thing removing it.
    #
    # ``fields=new_data.keys()`` narrows the stamps to the dimensions THIS save is writing: a marker
    # naming a column the body does not carry describes nothing that is happening here.
    #
    # Note what ``method_stamps`` returns for a dimension that IS being written with no marker for it:
    # ``{"method": "UNRECORDED"}``, deliberately, and not nothing. An old web build or an installed
    # handset editing a dimension therefore writes an explicit UNRECORDED, which is honest, is
    # distinguishable from a machine reading, and is never the false human claim. A burst of them
    # after this ships is the fleet degrading correctly, not a bug.
    stamps = method_stamps(new_data.pop(MARKER_BODY_KEY, None), fields=new_data.keys())

    provenance: dict[str, Any] = {}
    if isinstance(previous_extra.get("fieldProvenance"), dict):
        provenance = dict(previous_extra["fieldProvenance"])

    stamp = {
        "by": get_value(user, "id"),
        "byName": get_value(user, "name"),
        "at": datetime.now(UTC).isoformat(),
    }

    for field, value in new_data.items():
        if field in PROVENANCE_SKIP_FIELDS or is_empty_value(value):
            continue
        previous_value = get_value(previous, field) if previous is not None else None
        if previous is None or is_empty_value(previous_value) or not values_match(previous_value, value):
            # INSIDE THIS LOOP AND NOWHERE ELSE. The loop only fires for a field whose value actually
            # changed, which is the guard against the worst way this feature can go wrong: both web
            # forms re-send every dimension on every save, so a client that blanket-sent
            # ``{"method": "TYPED"}`` for every box would otherwise launder every accepted vision-model
            # measurement on the record into an apparent human one the next time somebody fixed a typo
            # in ``remarks``. An untouched dimension keeps the stamp it already had, method included.
            #
            # ``stamp | stamps.get(...)`` and not the reverse, so the method joins ``{by, byName, at}``
            # instead of replacing them. The merge also gives every field its OWN dict — this line
            # used to assign the one shared ``stamp`` object to every changed field, which meant any
            # later per-field edit of the provenance blob silently edited all of them.
            provenance[field] = stamp | stamps.get(field, {})

    if provenance:
        base_extra["fieldProvenance"] = provenance
    if base_extra:
        # Prisma Json columns must receive a Json wrapper, not a raw dict.
        new_data["extraMetadata"] = Json(base_extra)
    else:
        new_data.pop("extraMetadata", None)


def resubmit_status(record: Any, user: Any, data: dict[str, Any]) -> dict[str, Any]:
    """When the CREATOR edits a record a reviewer sent back (NEEDS_REVISION), the edit IS the
    resubmission: flip it back to PENDING so it re-enters the review queue. An explicit status in
    the payload always wins, and other editors (admins tidying up, contributors filling gaps)
    never flip the status. Call after guard_record_edit, before the prisma update. Mutates and
    returns ``data``; a no-op for records without a status column."""
    from app.core.deps import get_value

    if "status" in data:
        return data
    current = get_value(record, "status")
    if str(getattr(current, "value", current)) != "NEEDS_REVISION":
        return data
    creator_id = get_value(record, "createdById")
    if creator_id is None or creator_id != get_value(user, "id"):
        return data
    data["status"] = "PENDING"
    return data


def review_update(status_value: str, notes: str | None, reviewer_id: str) -> dict[str, Any]:
    return {
        "status": status_value,
        "reviewNotes": notes,
        "reviewedById": reviewer_id,
        "reviewedAt": datetime.now(UTC),
    }


def relation_filter(field: str, value: str | None) -> dict[str, Any]:
    return {field: value} if value else {}


def media_relation_data(record_type: str | None, record_id: str | None) -> dict[str, Any]:
    if not record_type or not record_id:
        return {}
    normalized = record_type.lower()
    field_map = {
        "artisan": "artisanId",
        "craft": "craftId",
        "workshop": "workshopId",
        "product": "productId",
        "tool": "toolId",
        "questionnaire": "questionnaireInterviewId",
        "questionnaireinterview": "questionnaireInterviewId",
    }
    field = field_map.get(normalized)
    return {field: record_id} if field else {}

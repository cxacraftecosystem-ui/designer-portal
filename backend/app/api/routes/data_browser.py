"""Virtual data browser: a lazily-explorable file-system view over the repository.

Four endpoints, all gated by the dataset-download permission AND by row visibility (see
:class:`Scope`): the permission decides whether an account may download data at all, the scope
decides whose data that is:

- ``GET /data/tree?path=...``      one level of the virtual tree (folders + files), plus
                                   server-resolved breadcrumbs and, on record folders, an
                                   ``info`` panel of human-labelled fields
- ``GET /data/manifest?path=...``  the flattened subtree below a path, same shape as
                                   ``/export/dataset`` (clients zip client-side), filterable by
                                   an ``include`` CSV of text,images,videos,audios,transcripts
- ``GET /data/report?path=...``    relational report of the subtree (Workshops/Crafts/Artisans/
                                   Products/Processes/Tools/Questionnaires/Transcripts/Media)
                                   as ``format=json`` sheets or a styled ``format=xlsx`` workbook
- ``GET /data/media/{id}/download``  one media file; audio is converted to .mp4 (AAC) on the fly

Tree layout — folder *paths* use record ids so navigation is unambiguous; folder *names* are
always the clean human name (workshop title, artisan name, product name, ...), never an id.
Duplicate display names within one level get a numeric suffix: "Name (2)".

The root is not a folder listing but a TAXONOMY chooser (see ``TAXONOMIES``): the same repository
browsed three ways. ``by-workshop`` is the default one the client opens on.

    ''                     -> by-workshop | by-uploader | by-type

``by-workshop`` — the hierarchy: workshop, craft, artisan, then that artisan's work.

    by-workshop                                 -> one folder per workshop (name = title)
    by-workshop/<wid>                           -> one folder per craft (the workshop's linked
                                                   crafts plus its artisans' crafts; artisans with
                                                   no craft go under 'No craft'), '_misc' (shown
                                                   "Miscellaneous") and details.txt
    by-workshop/<wid>/crafts/<cid>              -> the workshop's artisans having that craft
    .../crafts/<cid>/artisans/<aid>             -> products | tools | questionnaire | misc
    .../products/<pid>                          -> details.txt + media + 'processes' (when any)
    .../products/<pid>/processes/<procid>       -> details.txt + process media + per-step folders
    .../products/<pid>/processes/<procid>/<sid> -> notes.txt + step media
    .../tools/<tid>                             -> details.txt + media
    .../questionnaire/<iid>                     -> answers.txt + per-question audio clips

``by-uploader`` — who recorded what, always scoped to one workshop so "their media" means the
media they put into THAT workshop.

    by-uploader                                 -> one folder per workshop (name = title)
    by-uploader/<wid>                           -> one folder per researcher who uploaded media to,
                                                   or authored a record in, that workshop
    by-uploader/<wid>/<uid>                     -> artisans | products | tools | questionnaire
                                                   | media
    by-uploader/<wid>/<uid>/<branch>            -> one '<record name>.txt' per record of that type
                                                   they created in that workshop
    by-uploader/<wid>/<uid>/media               -> images | videos | audios | transcripts
                                                   | documents | other
    by-uploader/<wid>/<uid>/media/<slug>        -> their files of that kind in that workshop

``by-type`` — every file in the repository grouped purely by what it is.

    by-type                -> images | videos | audios | transcripts | documents | other
    by-type/<slug>         -> the files themselves. 'transcripts' is not a MediaType: it is every
                              media row carrying transcript text, rendered as .transcript.md files
                              so the folder holds transcripts rather than the audio they came from

Pre-taxonomy paths still resolve so links saved before the switcher existed keep working:
``workshops/...`` is the same lister as ``by-workshop/...``, ``media-types/...`` the same as
``by-type/...``, and ``users`` keeps its own older shape (one folder per uploader, then
artisans | products | tools | workshops | questionnaire | misc, repository-wide rather than
per workshop). ``_LEGACY_TAXONOMY`` maps each old root onto the taxonomy that replaced it so the
switcher still highlights the right tab. Inside ``by-workshop`` the pre-craft-level
``<wid>/artisans/<aid>`` path also still resolves.

The query/mapping style mirrors export.py's dataset manifest, but every level is lazy: a /tree
call only runs the queries that level needs (each bounded by ``TAKE``).
"""

import asyncio
import io
import json
import re
from collections.abc import AsyncIterator, Sequence
from dataclasses import dataclass
from typing import Any
from urllib.parse import quote

from fastapi import APIRouter, Depends, HTTPException, Query, status
from fastapi.responses import (
    FileResponse,
    JSONResponse,
    RedirectResponse,
    Response,
    StreamingResponse,
)
from starlette.background import BackgroundTask

from app.core.db import db
from app.core.deps import (
    can_export_design_workshop_data,
    can_view_design_workshop_data,
    require_dataset_downloader,
)
from app.services import design_workshop_data as dw, memory_budget
from app.services.concurrency import gather_reads
from app.services.custom_sections import load_definition_or_empty
from app.services.dictation_consent import MEDIA_TAG as DESIGN_WORKSHOP_MEDIA_TAG
from app.services.media_naming import (
    RESERVED_NAMES as _RESERVED_NAMES,
    clip as _clip,
    display_filename,
    display_stem,
    folder_order,
    interview_record,
    safe_chars as _safe_chars,
    unique_display_filename,
    unique_display_stem,
    unique_name,
)
from app.services.record_fields import (
    MEDIA_COLOR,
    MEDIA_COLUMNS,
    OVERVIEW_COLOR,
    PROVENANCE_COLUMNS,
    SPECS,
    TRANSCRIPT_COLOR,
    artisan_names,
    cell as _cell,
    date_str as _date,
    enum_label as _enum_label,
    ev as _ev,
    human_size as _human_size,
    info_panel,
    info_text as _info_text,
    interview_label as _interview_label,
    media_row,
    provenance_row,
    sheet_columns,
    sheet_row,
)
from app.services.records import owned_or_granted_where
from app.services.rich_text import plain_from_stored
from app.services.s3 import ObjectTooLarge, discard_temp, download_to_temp, head_object
from app.services.transcript_format import transcript_cell
from app.services.xlsx_report import XLSX_MIME, build_report_workbook

router = APIRouter(
    prefix="/data",
    tags=["data-browser"],
    dependencies=[Depends(require_dataset_downloader)],
)

# Upper bound for any single level's listing query, keeping every /tree call cheap.
TAKE = 500
# Safety valve for /manifest walks so a pathological subtree cannot run away.
MAX_MANIFEST_FILES = 20000
MAX_WALK_DEPTH = 16
# Manifest walks visit sibling subtrees concurrently; this caps in-flight DB queries safely
# below the Prisma connection limit (10 per worker).
_WALK_SEM = asyncio.Semaphore(5)
# Refuse in-process audio conversion beyond this size — decoding a very large WAV would exhaust
# the t3.micro's RAM; the client falls back to the original object URL.
#
# 32 MiB, DOWN FROM 200 MiB, and the old number was not a bound at all. docs/SCALABILITY.md §5.1
# fix 3 asks for exactly this figure, and the measurement behind it is in that section: five live
# objects are over 131 MiB and THREE OF THEM (131.2, 151.4, 156.0 MiB) sat under the 200 MiB
# constant and were therefore ADMITTED — each one decoded to PCM by ffmpeg, several times its
# compressed size, in a single-worker uvicorn on a 1 GiB box. A cap that admits a file the box
# cannot hold is a comment, not a cap.
#
# This is the CEILING, not the answer. `convert_ceiling_bytes()` is what the route asks, and it
# lowers this further when the box says it has less; a constant cannot know what else is in flight.
MAX_CONVERT_BYTES = 32 * 1024 * 1024
# The same treatment for the untouched-object fallback, which had NO cap of any kind — it read the
# whole object into the heap and handed it to `Response(content=...)`, for any size, whenever
# `media.url` was falsy. That path now streams from disk, so this bound is about DISK rather than
# RAM: it exists so one pathological object cannot fill the box's root volume. The largest object
# in this deployment is 668.44 MiB (MEASURED, §5.1), so 1 GiB admits everything in the archive
# today and refuses only what nothing has ever uploaded.
MAX_DOWNLOAD_BYTES = 1024 * 1024 * 1024
# Per-sheet row cap for /data/report (a truncation note row is appended when hit).
REPORT_TAKE = 5000
# Pseudo craft-folder id gathering a workshop's artisans that have no craft assigned.
NO_CRAFT = "_none"

# The ASCII reduction of a name, used ONLY for the fallback parameter of a Content-Disposition
# header (see _content_disposition). Folder and file names themselves are NOT reduced to ASCII —
# see _seg.
_ASCII_ONLY = re.compile(r"[^A-Za-z0-9 _.\-]+")

# Name caps. File systems limit a name to ~255 BYTES, not characters, and a Devanagari character
# costs three of them, so both limits are applied. The character rules themselves now live in
# services/media_naming.py, which needs the identical answer for the file names it builds.
_MAX_SEG_CHARS = 80
_MAX_SEG_BYTES = 200

# MediaType -> the `include` CSV token that selects it in /data/manifest.
_TYPE_TOKEN = {
    "IMAGE": "images",
    "VIDEO": "videos",
    "AUDIO": "audios",
    "PDF": "documents",
    "DOCUMENT": "documents",
    "OTHER": "other",
}

# linkedRecordType tags considered "attached to a typed record" (everything else is misc).
#
# THE DESIGN-WORKSHOP TAG JOINED THIS LIST ON 2026-08-31 AND THAT IS A BUG FIX, NOT AN ADDITION.
# Every photograph and every dictation recording taken inside a design & prototype workshop carries
# ``linkedRecordType="designWorkshop"`` (``dictation_consent.MEDIA_TAG``), and while that string was
# absent from this list the ``misc`` clause below — ``linkedRecordType NOT IN (…)`` — swept all of
# them into "Miscellaneous", beside the genuinely unattached files. They were browsable and they
# were anonymous: no workshop, no stage, no field. They are now a typed branch of their own
# (``_USER_TYPE_WHERE["designworkshops"]``) and are named by ``dw.media_attributions``.
#
# BOTH SPELLINGS, and this is the same rule ``media.ORPHAN_TAG_TYPES`` writes down. The clients send
# the camelCase ``designWorkshop``; ``POST /media/{id}/relink`` lowercases whatever it is handed
# before storing it, so rows written by a recovery carry ``designworkshop``. Listing one and not the
# other would leave half of them in Miscellaneous — the exact defect, half-fixed, which is worse
# than not fixing it because it looks fixed.
_DESIGN_WORKSHOP_TAGS = [DESIGN_WORKSHOP_MEDIA_TAG, DESIGN_WORKSHOP_MEDIA_TAG.lower()]

# The seven legacy record tags, WITHOUT the design-workshop pair. Kept separate because the misc
# clause has to be able to answer both readings — see :func:`_user_type_where`.
_TYPED_TAGS_LEGACY = [
    "artisan",
    "product",
    "process",
    "processstep",
    "tool",
    "workshop",
    "questionnaire",
    "questionnaireinterview",
]

_TYPED_TAGS = [*_TYPED_TAGS_LEGACY, *_DESIGN_WORKSHOP_TAGS]

_USER_TYPE_WHERE: dict[str, dict[str, Any]] = {
    "artisans": {"linkedRecordType": "artisan"},
    "products": {"linkedRecordType": {"in": ["product", "process", "processstep"]}},
    "tools": {"linkedRecordType": "tool"},
    "workshops": {"linkedRecordType": "workshop"},
    "questionnaire": {"linkedRecordType": {"in": ["questionnaire", "questionnaireinterview"]}},
    # BOTH READINGS OF "belongs to a design workshop", because the two columns answer different
    # questions and a researcher wants the union. The tag pair says which stage photographs and
    # dictation belong to the workshop; ``designWorkshopId`` says which workshop a MISCELLANEOUS
    # upload was filed under from the dropdown on that form. A file can have one, both or neither —
    # see the column's own note in schema.prisma — so a branch reading only one of them would hide
    # whichever half the uploader did not use.
    "designworkshops": {
        "OR": [
            {"linkedRecordType": {"in": _DESIGN_WORKSHOP_TAGS}},
            {"designWorkshopId": {"not": None}},
        ]
    },
    # NOTHING FILED UNDER A DESIGN WORKSHOP IS MISCELLANEOUS — for a caller who has the
    # ``designworkshops`` branch to find it in. The ``designWorkshopId`` half has to be said with an
    # AND rather than left to ``_TYPED_TAGS``: a file uploaded on the Miscellaneous Media form and
    # filed to a workshop from its dropdown carries the COLUMN and no tag at all, so
    # ``linkedRecordType IS NULL`` matches it here — it would appear in both branches at once, which
    # reads to a researcher as two copies of one file rather than as one file with two readings.
    #
    # THIS ENTRY IS THE NARROW READING AND IT IS NOT WHAT EVERY CALLER GETS. See
    # :func:`_user_type_where`, which is what the route asks: an account that may not open the
    # design-workshop branch keeps seeing these files here, exactly as it did before this change.
    # Narrowing the clause for everybody would have made files vanish from a folder somebody was
    # already browsing, to enforce a rule about a screen they cannot reach.
    "misc": {
        "AND": [
            {"OR": [{"linkedRecordType": None}, {"linkedRecordType": {"not_in": _TYPED_TAGS}}]},
            {"designWorkshopId": None},
        ]
    },
}

#: The pre-2026-08-31 ``misc`` clause: everything not attached to one of the seven legacy records.
_MISC_WHERE_LEGACY: dict[str, Any] = {
    "OR": [
        {"linkedRecordType": None},
        {"linkedRecordType": {"not_in": _TYPED_TAGS_LEGACY}},
    ]
}


_MEDIA_TYPE_WHERE: dict[str, dict[str, Any]] = {
    "images": {"mediaType": "IMAGE"},
    "videos": {"mediaType": "VIDEO"},
    "audios": {"mediaType": "AUDIO"},
    "documents": {"mediaType": {"in": ["PDF", "DOCUMENT"]}},
    "other": {"mediaType": "OTHER"},
}

# The three ways the same repository can be browsed. The client renders these as a
# switcher at the root; `default` decides which one it opens on.
TAXONOMIES: list[dict[str, Any]] = [
    {
        "id": "by-workshop",
        "name": "By workshop",
        "path": "by-workshop",
        "description": (
            "Workshop, then craft, then artisan, then that artisan's products, tools and "
            "questionnaires. Products open into their processes. A workshop's loose media "
            "sits in Miscellaneous, one level under the workshop."
        ),
        "default": True,
    },
    {
        "id": "by-uploader",
        "name": "By uploader",
        "path": "by-uploader",
        "description": (
            "Workshop, then the researcher who uploaded, then everything they recorded — "
            "their entries with the fields they filled in, and their media by type."
        ),
        "default": False,
    },
    {
        "id": "by-type",
        "name": "By media type",
        "path": "by-type",
        "description": (
            "Every file grouped purely by what it is: audios, videos, images, transcripts, "
            "documents."
        ),
        "default": False,
    },
    # A ROOT OF ITS OWN RATHER THAN A BRANCH UNDER ``by-workshop``, and the choice matters because
    # the obvious-looking alternative silently loses records.
    #
    # ``Workshop`` and ``DesignWorkshop`` ARE DIFFERENT TABLES. The first is the legacy repository
    # workshop the other three taxonomies are built from; the second is the twenty-two-stage record
    # this product is named after.
    #
    # THE ONE LINK BETWEEN THEM IS ``DesignWorkshop.workshopId``, AND IT IS NULLABLE — nothing goes
    # the other way at all. So a design workshop hung under ``by-workshop`` would need a parent most
    # of them do not have, and every one that does not would end up under a "No workshop"
    # pseudo-folder. A taxonomy whose rows are mostly in the bucket for rows that do not fit is not a
    # taxonomy. (This paragraph said "nothing joins them" until 2026-08-31. The column is real —
    # ``record_filters``' workshop scope narrows this bucket through it — and the conclusion is
    # unchanged, but a reader who took the stronger claim on trust would go looking for a join that
    # is sitting right there.)
    #
    # It is also the honest answer to what a researcher is asking. The other three ask "where in the
    # repository does this file sit"; this one asks "what did this fortnight of fieldwork produce",
    # and its levels are the STAGES, which no other root has. Kept OUT of the default so the screen
    # opens exactly as it does today for everybody, including the accounts that never see this root
    # at all (see :func:`_taxonomies_for`).
    {
        "id": "by-design-workshop",
        "name": "By design workshop",
        "path": "by-design-workshop",
        "description": (
            "The twenty-two stages of each design & prototype workshop: every answer as a table, "
            "the designer's own questions, and each photograph under the stage and field it "
            "answers."
        ),
        "default": False,
    },
]

# Display labels for the static (non-record) folder slugs used across the tree.
_CATEGORY_LABEL: dict[str, str] = {
    "by-workshop": "By workshop",
    "by-uploader": "By uploader",
    "by-type": "By media type",
    "by-design-workshop": "By design workshop",
    "designworkshops": "Design workshops",
    "stages": "Stages",
    "transcripts": "Transcripts",
    "media": "Media",
    "workshops": "Workshops",
    "users": "Users",
    "media-types": "Media types",
    "crafts": "Crafts",
    "artisans": "Artisans",
    "products": "Products",
    "tools": "Tools",
    "processes": "Processes",
    "questionnaire": "Questionnaire",
    "misc": "Miscellaneous",
    "_misc": "Miscellaneous",
    "images": "Images",
    "videos": "Videos",
    "audios": "Audios",
    "documents": "Documents",
    "other": "Other",
}


# ---------------------------------------------------------------------------
# Small helpers (same style as export.py; re-implemented locally on purpose —
# export.py is owned elsewhere and must not be touched).
# ---------------------------------------------------------------------------


def _seg(value: str | None, fallback: str) -> str:
    """One path segment of a folder or file name, safe for a filesystem and for a zip.

    This used to be ``_SAFE.sub("_", …)`` against ``[^A-Za-z0-9 _.-]``, which replaced every
    character outside ASCII. For a repository whose subject IS Indian craft that was the wrong
    failure: an artisan named in Devanagari became ``_ _``, and several such artisans collapsed onto
    the same segment, so the tree and the exported zip showed a row of identical ``_`` folders and a
    researcher could not tell whose was whose. Names are the data here, not decoration.

    So the rule is inverted. Instead of allowing a list of characters, it removes the ones that are
    genuinely unusable — the two path separators and the punctuation Windows reserves, plus control
    and format characters by Unicode category — and keeps everything else, in any script. The zero
    width joiner and non joiner are deliberately exempted from the format-character sweep: they are
    invisible, but in Devanagari and other Indic scripts they select conjunct and half forms, so
    dropping them misspells the very names this change exists to preserve.

    That character rule and the two-limit trim now live in services/media_naming.py, which applies
    the identical reasoning to the file names it derives; this stays the folder-segment entry point.
    """
    cleaned = _safe_chars((value or "").strip()).strip(" .")
    cleaned = _clip(cleaned, _MAX_SEG_CHARS, _MAX_SEG_BYTES).strip(" .")

    if not cleaned:
        return fallback
    # CON, PRN, LPT1 … are refused by Windows with or without an extension, so a craft or an artisan
    # legitimately called "Aux" would produce a folder that cannot be written on extraction.
    if cleaned.split(".")[0].upper() in _RESERVED_NAMES:
        return f"{cleaned}_"
    return cleaned


def _content_disposition(name: str) -> str:
    """An attachment header that survives a non-ASCII filename.

    Necessary because :func:`_seg` now keeps Devanagari (and every other script). An HTTP header
    field is latin-1 by definition, so interpolating those bytes straight into `filename="…"`
    either raises on encode or ships mojibake — a download that used to work would start failing
    for exactly the names the Unicode fix set out to preserve, which would be a worse bug than the
    one it replaced.

    RFC 6266 is built for this: `filename=` carries an ASCII reduction for old clients, and
    `filename*=UTF-8''…` carries the real name percent-encoded. Every current browser prefers the
    starred form, so the researcher gets the artisan's actual name and nothing breaks in between.
    """
    ascii_name = _ASCII_ONLY.sub("_", name).strip(" ._") or "download"
    return f"attachment; filename=\"{ascii_name}\"; filename*=UTF-8''{quote(name, safe='')}"


def _join(parent: str, name: str) -> str:
    return f"{parent}/{name}" if parent else name


def _norm(path: str) -> str:
    return "/".join(s for s in (path or "").split("/") if s)


# Pre-taxonomy roots map onto the taxonomy that replaced them, so an old saved link
# still reports the right active tab in the switcher.
_LEGACY_TAXONOMY = {"workshops": "by-workshop", "users": "by-uploader", "media-types": "by-type"}


def _taxonomy_of(norm: str) -> str | None:
    """Which taxonomy a path sits in, or None at the root (where none is chosen yet)."""
    head = norm.split("/", 1)[0] if norm else ""
    if not head:
        return None
    if head in _LEGACY_TAXONOMY:
        return _LEGACY_TAXONOMY[head]
    return head if any(t["id"] == head for t in TAXONOMIES) else None


# ---------------------------------------------------------------------------
# Row visibility.
#
# The router-level dependency answers "may this account download data at all". It does NOT answer
# "WHOSE data" — and ``canDownloadDataset`` is a GRANTABLE boolean, so a researcher can hold it
# without ranking Professor+. Until this scope existed every /data endpoint handed such a
# researcher the whole repository, while /export/dataset — the sibling endpoint doing the same job
# — filtered by ``owned_or_granted_where``. The permission means "download the data you can SEE", so the
# same filter now rides every query behind /tree, /manifest, /report and /media/{id}/download.
#
# Both filters are EMPTY for Professor and above (``owned_or_granted_where`` returns ``{}`` for them) and
# every helper below short-circuits on an empty filter back to the exact call it made before, so
# for professors, admins and the master admin this is a no-op down to the query shape.
#
# Crafts are deliberately NOT filtered: they are shared vocabulary (GET /crafts lists them to every
# authenticated user) and ``Craft.createdById`` is nullable, so filtering them would hide the
# taxonomy the tree is built from rather than protect anybody's data.
# ---------------------------------------------------------------------------


@dataclass(frozen=True)
class Scope:
    """The row-visibility filters for one /data request.

    ``records`` filters record tables on their ``createdById`` owner column; ``media`` filters
    MediaFile on ``uploadedById`` — the same split ``/dashboard/stats`` and ``GET /media`` use.
    """

    records: dict[str, Any]
    media: dict[str, Any]
    #: May this request SEE design-workshop stage data? Professor, Admin, Master Admin.
    #:
    #: A THIRD FIELD ON THE SCOPE RATHER THAN A ROUTER DEPENDENCY, because it is not a door — it is a
    #: narrowing of what is behind one. ``/data`` stays mounted behind ``require_dataset_downloader``
    #: and every account that reaches it today still reaches it; this decides whether the
    #: ``by-design-workshop`` root, its sheets and its media branch are part of the answer. Putting
    #: it here means every lister, the manifest walk and the report all consult the same value, and
    #: none of them can forget to.
    #:
    #: FALSE FOR A GRANTED RESEARCHER, which is the population this narrowing exists for.
    #: ``canDownloadDataset`` is a per-account boolean an admin hands to a researcher who needs the
    #: seven legacy tables; it carries no seniority, and stage data — artisan dictation, consent
    #: state, unpublished prototype work — is gated on RANK. See
    #: ``deps.can_view_design_workshop_data``.
    design_workshops: bool = False
    #: May this request take that data OUT — the .xlsx sheets and the manifest the client zips?
    #: Admin and Master Admin only. A professor is deliberately True above and False here; see
    #: ``deps.can_export_design_workshop_data`` for why the two acts are not one permission.
    design_workshop_downloads: bool = False

    @property
    def restricted(self) -> bool:
        """False for Professor+/admins, whose filters are empty (they see everything).

        DELIBERATELY UNCHANGED BY THE TWO DESIGN-WORKSHOP FLAGS. This property means "are the ROW
        filters narrowing anything", and every helper in this module short-circuits on it back to
        the exact query it made before. Folding a capability flag into it would make a professor —
        whose row filters are empty and always have been — take the restricted branch of six
        queries, changing their query plans to protect data the flags already keep out of the
        result.
        """
        return bool(self.records or self.media)

    def for_download(self) -> "Scope":
        """The same scope as it applies to a path that HANDS OVER A FILE.

        On a download, viewing IS exporting: the manifest is the list of files the browser zips, so
        anything listed leaves the building. Collapsing the two flags here rather than testing them
        separately at four call sites means the manifest walk can go on asking one question —
        "is the design-workshop root part of this tree?" — and get the right answer on both routes.
        """
        return Scope(
            records=self.records,
            media=self.media,
            design_workshops=self.design_workshop_downloads,
            design_workshop_downloads=self.design_workshop_downloads,
        )


async def _scope_for(user: Any) -> Scope:
    """Both row-visibility filters for one /data request, resolved as one wave.

    **IT SAVES NO ROUND TRIP TODAY, AND THAT IS WORTH KNOWING BEFORE SOMEBODY QUOTES IT AS ONE.**
    Only the ``media`` half ever queries. ``records.owned_or_granted_where`` is dictionary work for
    a record owner column and reads the design-workshop tag ids only for ``uploadedById`` — and its
    own comment states that the record variant must NOT grow a lookup to match, because that one
    rides every /export CSV and every /data page. So below professor this pair costs one query
    whether it is gathered or awaited in turn, and for Professor and above it costs none.

    What the wave buys is that the pair is now STATED as a pair, on a function that sits on every
    /data route (tree, manifest, report, download). If the record half ever does acquire a read, it
    joins a wave instead of quietly adding a second round trip to four routes at once. That is a
    claim about shape, not about latency; do not cite it as a measured saving.
    """
    record_where, media_where = await gather_reads(
        owned_or_granted_where(user),
        owned_or_granted_where(user, owner_field="uploadedById"),
    )
    # Both capability answers are pure role reads — no query, no round trip — so they ride along
    # with the wave rather than being asked for again at each of the four routes.
    return Scope(
        records=record_where,
        media=media_where,
        design_workshops=can_view_design_workshop_data(user),
        design_workshop_downloads=can_export_design_workshop_data(user),
    )


def _user_type_where(slug: str, scope: Scope) -> dict[str, Any]:
    """The media clause for one branch of the legacy ``users`` taxonomy, as THIS caller sees it.

    ONE BRANCH IS CAPABILITY-DEPENDENT AND IT IS ``misc``. Moving design-workshop files out of
    Miscellaneous is the whole point of the ``designworkshops`` branch — a file in a folder that
    names its workshop, stage and field is worth more than the same file in a folder that names
    nothing. But an account that may NOT open that branch would simply have lost the files: they
    were listed yesterday, they would not be listed today, and nothing on screen would say why. So
    that account keeps the clause it has always had. Nobody sees less than before; the accounts
    entitled to the design-workshop surface see the same files better filed.
    """
    if slug == "misc" and not scope.design_workshops:
        return _MISC_WHERE_LEGACY
    return _USER_TYPE_WHERE[slug]


def _taxonomies_for(scope: Scope) -> list[dict[str, Any]]:
    """The taxonomy switcher as THIS account sees it.

    ``by-design-workshop`` is dropped for an account that may not read design-workshop data — which
    is the researcher holding a ``canDownloadDataset`` grant without the rank behind it. Offering
    the tab and refusing what is inside it would be the failure ``frontend/lib/permissions.ts``'s
    own rule names: never offer what the API refuses. The other three are unchanged for everybody,
    so nothing any account can do today changes shape.
    """
    if scope.design_workshops:
        return TAXONOMIES
    return [taxonomy for taxonomy in TAXONOMIES if taxonomy["id"] != "by-design-workshop"]


def _and(where: dict[str, Any], extra: dict[str, Any]) -> dict[str, Any]:
    """``where`` AND ``extra`` — returning ``where`` untouched when there is nothing to add, which
    is what keeps every query identical for an unrestricted caller."""
    if not extra:
        return where
    if not where:
        return dict(extra)
    return {"AND": [where, extra]}


async def _visible_only(
    delegate: Any, records: list[Any], scope_where: dict[str, Any]
) -> list[Any]:
    """Filter an already-loaded (relation-include-derived) list down to the rows the caller may see.

    Runs NO query at all when the scope is unrestricted; otherwise one id-set query decides the
    whole list and the ORIGINAL objects are returned, so the relations loaded with them survive.
    """
    if not scope_where or not records:
        return records
    rows = await delegate.find_many(
        where={"AND": [{"id": {"in": [r.id for r in records]}}, scope_where]}
    )
    allowed = {r.id for r in rows}
    return [r for r in records if r.id in allowed]


def _uniq(name: str, used: set[str]) -> str:
    """Keep FOLDER names unique within one level: "Name", "Name (2)", "Name (3)", ...

    Dedupes case-insensitively (zip extraction on Windows is case-insensitive) and keeps a trailing
    extension at the end, for the craft or artisan whose name happens to contain a dot. Files no
    longer come through here: they are numbered by ``media_naming.unique_name``, in the same "-2"
    the capture screens would have written, which reads as part of the name rather than as an
    apology for one.
    """
    key = name.lower()
    if key not in used:
        used.add(key)
        return name
    head, slash, leaf = name.rpartition("/")
    stem, dot, ext = leaf.rpartition(".")
    n = 2
    while True:
        candidate = f"{head}{slash}{stem} ({n}).{ext}" if dot else f"{name} ({n})"
        if candidate.lower() not in used:
            used.add(candidate.lower())
            return candidate
        n += 1


# Extensions of more than one part, which a split on the last dot would cut in half.
_COMPOUND_EXTENSIONS = (".transcript.md",)


def _split_leaf(name: str) -> tuple[str, str]:
    """A file name as (stem, extension), so a duplicate can be numbered between the two."""
    for compound in _COMPOUND_EXTENSIONS:
        if name.lower().endswith(compound):
            return name[: -len(compound)], name[-len(compound) :]
    stem, dot, ext = name.rpartition(".")
    return (stem, f".{ext}") if dot and stem else (name, "")


def _folder(name: str, path: str, record_type: str = "category") -> dict[str, Any]:
    return {"name": name, "path": path, "kind": "folder", "recordType": record_type}


def _text(parent: str, name: str, content: str | None) -> dict[str, Any] | None:
    """A synthesised .txt file in the browser tree — and the ONE rich-text bypass in this module.

    Every other caller here hands over ``_info_text(info_panel(...))``, which has already been
    through ``record_fields.cell`` and is therefore already flattened. The process-step ``notes.txt``
    below does not: it passes a raw ``String?`` column straight in. Since the larger free-text
    columns can now hold a serialised rich document (see ``rich_text.plain_from_stored``), that one
    caller would have written a file of JSON braces into a downloaded folder while its four siblings
    wrote clean prose.

    The flattening is done HERE rather than at that call site on purpose: this is the funnel every
    synthetic text file in the tree goes through, so the next one somebody adds is covered without
    them having to know any of the above. A plain string is returned unchanged, by identity.
    """
    flattened = plain_from_stored(content)
    if not (flattened or "").strip():
        return None
    return {"name": name, "path": _join(parent, name), "kind": "file", "content": flattened}


def _media_entries(
    parent: str,
    media: list[Any],
    *,
    record_type: str | None = None,
    record_name: str | None = None,
    step_number: int | None = None,
    step_name: str | None = None,
) -> list[dict[str, Any]]:
    """The files in one folder, each shown under a name derived from the record it belongs to.

    The uploaded name is a code the capture screen minted — ``D_SEC_GIRIRAJ_001046_010720261824.wav``
    — and it survives a download into a folder that explains nothing. ``display_filename`` rebuilds
    it as ``Artisan-Giriraj-Prasad-Chhipa-Interview-Section-D-010720261824.wav`` from the row and its
    relations; nothing is renamed in storage, so every URL and every objectKey is untouched. The
    uploaded name rides along in ``originalFilename`` because a researcher reconciling an export
    against files already on their laptop still has to match the two up.

    A caller that already holds the parent record names it here, which is both cheaper than loading
    the relations back off the row and more precise — the step number and step name in particular
    exist nowhere on the media row.

    The names are decided in ``folder_order`` — createdAt, then id — and not in whatever order the
    rows arrived in. That fixes both halves of the answer for good: which of four takes recorded in
    one minute keeps the unnumbered name, and which photo of a batch is "Photo-1" when the uploaded
    name carries no index of its own.
    """
    used: set[str] = set()
    entries: list[dict[str, Any]] = []
    for position, m in enumerate(folder_order(media), start=1):
        name = unique_display_filename(
            m,
            used,
            record_type=record_type,
            record_name=record_name,
            step_number=step_number,
            step_name=step_name,
            position=position,
            fallback=m.id,
        )
        entries.append(
            {
                "name": name,
                "path": _join(parent, name),
                "kind": "file",
                "originalFilename": m.originalFilename,
                "mediaType": str(_ev(m.mediaType)),
                "mediaId": m.id,
                "url": m.url,
                "sizeBytes": int(m.sizeBytes) if m.sizeBytes is not None else None,
                "transcriptAvailable": bool((m.transcriptText or "").strip()),
                # Internal (stripped from /tree responses): lets /manifest emit transcript files
                # without a second query.
                "_transcriptText": m.transcriptText,
            }
        )
    return entries


# The relations a display name is read from when the caller has no parent record to hand: the flat
# listers (by media type, by uploader, a user's media) show files from all over the repository at
# once. Nested for interviews because a questionnaire clip is named after the ARTISAN it is with,
# and that is two hops from the media row.
_NAMING_INCLUDE: dict[str, Any] = {
    "artisan": True,
    "craft": True,
    "workshop": True,
    "product": True,
    "tool": True,
    "questionnaireInterview": {"include": {"artisans": {"include": {"artisan": True}}}},
}


async def _media(where: dict[str, Any], scope: Scope, *, named: bool = False) -> list[Any]:
    """Media rows for one folder. ``named`` loads the relations a display name needs.

    Off by default, and deliberately: the record-level folders pass the record they already hold
    straight to :func:`_media_entries`, so making every level pay for six relation loads — on a
    query the manifest walk repeats for every folder in the subtree — would buy nothing.
    """
    kwargs: dict[str, Any] = {}
    if named:
        kwargs["include"] = _NAMING_INCLUDE
    return await db.mediafile.find_many(
        where=_and(where, scope.media), take=TAKE, order={"createdAt": "asc"}, **kwargs
    )


async def _workshop_misc_media(wid: str, scope: Scope, *, named: bool = False) -> list[Any]:
    """Media that belongs to the WORKSHOP itself: nothing finer-grained claims it."""
    return await _media(
        {
            "AND": [
                {"artisanId": None},
                {"productId": None},
                {"toolId": None},
                {"questionnaireInterviewId": None},
                _record_media_where("workshopId", wid, ["workshop"]),
            ]
        },
        scope,
        named=named,
    )


async def _artisan_own_media(aid: str, scope: Scope) -> list[Any]:
    """Media that belongs to the ARTISAN itself, not to a product, tool or interview of theirs."""
    return await _media(
        {
            "OR": [
                {"AND": [{"linkedRecordType": "artisan"}, {"linkedRecordId": aid}]},
                {"AND": [{"artisanId": aid}, {"linkedRecordType": None}]},
            ]
        },
        scope,
    )


async def _artisan_display_name(aid: str) -> str | None:
    """The artisan's name for the file names in a legacy 'misc' listing, which loads no record.

    Half of an artisan's own media carries the string tag rather than the FK, so the ``artisan``
    relation is not there to read the name off; one lookup keeps those files named after the person
    instead of falling back to whatever the capture screen encoded in the upload.
    """
    artisan = await db.artisan.find_unique(where={"id": aid})
    return (getattr(artisan, "name", None) or "").strip() or None


def _record_media_where(fk_field: str, rec_id: str, tags: list[str]) -> dict[str, Any]:
    """Media attached to one record — via its typed FK column OR the string tag pair."""
    return {
        "OR": [
            {fk_field: rec_id},
            {"AND": [{"linkedRecordType": {"in": tags}}, {"linkedRecordId": rec_id}]},
        ]
    }


# ---------------------------------------------------------------------------
# Info panels per record type.
#
# Field lists, labels, colours and value coercion all live in
# app/services/record_fields.py so the browser's info card, the browser's in-folder
# table and the .xlsx report sheets can never drift apart. The thin wrappers below
# keep the call sites in this module readable.
# ---------------------------------------------------------------------------


def _workshop_info(ws: Any) -> dict[str, Any]:
    return info_panel("workshop", ws)


def _craft_info(c: Any) -> dict[str, Any]:
    return info_panel("craft", c)


def _artisan_info(a: Any) -> dict[str, Any]:
    return info_panel("artisan", a)


def _product_info(p: Any) -> dict[str, Any]:
    return info_panel("product", p)


def _tool_info(t: Any) -> dict[str, Any]:
    return info_panel("tool", t)


def _process_info(pr: Any) -> dict[str, Any]:
    return info_panel("process", pr)


def _artisan_names(interview: Any) -> list[str]:
    return artisan_names(interview)


def _interview_info(interview: Any) -> dict[str, Any]:
    return info_panel("interview", interview)


def _interview_answers(interview: Any, info: dict[str, Any]) -> str:
    header = _info_text(info)
    responses = sorted(
        interview.responses or [],
        key=lambda r: getattr(getattr(r, "question", None), "sortOrder", 0) or 0,
    )
    answers = []
    for r in responses:
        q = getattr(r, "question", None)
        prompt = getattr(q, "prompt", r.questionId) if q else r.questionId
        code = getattr(q, "sectionCode", "") if q else ""
        answers.append(f"[{code}] {prompt}\n  -> {r.answerText or ''}\n")
    return header + "\n\n" + "".join(answers)


async def _require(
    delegate: Any,
    rec_id: str,
    what: str,
    include: dict[str, Any] | None = None,
    scope_where: dict[str, Any] | None = None,
) -> Any:
    """Load one record by id or 404.

    With ``scope_where`` the row must ALSO satisfy the visibility filter, and one it does not is
    reported as "not found" rather than 403 — a browser path the caller may not open must not
    confirm that the record exists. Unrestricted callers keep the plain ``find_unique``.
    """
    kwargs: dict[str, Any] = {"where": {"id": rec_id}}
    if include:
        kwargs["include"] = include
    if scope_where:
        kwargs["where"] = {"AND": [{"id": rec_id}, scope_where]}
        record = await delegate.find_first(**kwargs)
    else:
        record = await delegate.find_unique(**kwargs)
    if record is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail=f"{what} not found")
    return record


# ---------------------------------------------------------------------------
# Server-resolved breadcrumbs: clean display names for every ancestor of a path
# (including the path itself). At most one find_unique per id segment.
#
# These lookups are deliberately unscoped: every endpoint resolves crumbs only AFTER the level (or
# report) it was asked for was produced, and that step already 404s on a path the caller may not
# open — so a name resolved here always belongs to a path they were allowed to reach.
# ---------------------------------------------------------------------------


async def _artisan_crumb_names(tail: list[str]) -> list[str]:
    """Crumb names for the segments from an artisan id downwards (products/tools/... subtree).

    ``tail`` starts right AFTER an 'artisans' keyword segment: [<aid>, <sub>, <rid>, ...].
    Shared by the legacy workshops/<wid>/artisans/... shape and the craft-ful
    workshops/<wid>/crafts/<cid>/artisans/... shape.
    """
    names: list[str] = []
    if not tail:
        return names
    artisan = await db.artisan.find_unique(where={"id": tail[0]})
    names.append(_seg(getattr(artisan, "name", None), tail[0]))
    if len(tail) >= 2 and tail[1] in ("products", "tools", "questionnaire", "misc"):
        sub = tail[1]
        names.append(_CATEGORY_LABEL[sub])
        if len(tail) >= 3:
            rid = tail[2]
            if sub == "products":
                p = await db.productdocumentation.find_unique(where={"id": rid})
                names.append(_seg(getattr(p, "productName", None), rid))
                if len(tail) >= 4 and tail[3] == "processes":
                    names.append("Processes")
                    if len(tail) >= 5:
                        pr = await db.process.find_unique(where={"id": tail[4]})
                        names.append(_seg(getattr(pr, "name", None), tail[4]))
                    if len(tail) >= 6:
                        st = await db.processstep.find_unique(where={"id": tail[5]})
                        names.append(_seg(getattr(st, "name", None), tail[5]))
            elif sub == "tools":
                t = await db.tooldocumentation.find_unique(where={"id": rid})
                names.append(_seg(getattr(t, "toolkitName", None), rid))
            elif sub == "questionnaire":
                i = await db.questionnaireinterview.find_unique(
                    where={"id": rid},
                    include={"artisans": {"include": {"artisan": True}}},
                )
                names.append(_seg(_interview_label(i), rid) if i else rid)
    return names


async def _crumb_names(segs: list[str]) -> list[str]:
    """One display name per path segment, resolved from the DB where the segment is an id."""
    names: list[str] = []
    if not segs:
        return names
    head = segs[0]

    if head == "by-uploader":
        names.append(_CATEGORY_LABEL["by-uploader"])
        if len(segs) >= 2:
            ws = await db.workshop.find_unique(where={"id": segs[1]})
            names.append(_seg(getattr(ws, "title", None), segs[1]))
        if len(segs) >= 3:
            u = await db.user.find_unique(where={"id": segs[2]})
            names.append(_seg(getattr(u, "name", None), segs[2]))
        if len(segs) >= 4:
            names.append(_UPLOADER_BRANCHES.get(segs[3], _CATEGORY_LABEL.get(segs[3], segs[3])))
        if len(segs) >= 5:
            names.append(_CATEGORY_LABEL.get(segs[4], segs[4]))
    elif head == "by-type":
        names.append(_CATEGORY_LABEL["by-type"])
        if len(segs) >= 2:
            names.append(_CATEGORY_LABEL.get(segs[1], segs[1]))
    elif head in ("workshops", "by-workshop"):
        names.append(_CATEGORY_LABEL["by-workshop"] if head == "by-workshop" else "Workshops")
        if len(segs) >= 2:
            ws = await db.workshop.find_unique(where={"id": segs[1]})
            names.append(_seg(getattr(ws, "title", None), segs[1]))
        if len(segs) >= 3:
            if segs[2] == "_misc":
                names.append(_CATEGORY_LABEL["_misc"])
            elif segs[2] == "crafts":
                names.append(_CATEGORY_LABEL["crafts"])
                if len(segs) >= 4:
                    if segs[3] == NO_CRAFT:
                        names.append("No craft")
                    else:
                        craft = await db.craft.find_unique(where={"id": segs[3]})
                        names.append(_seg(getattr(craft, "name", None), segs[3]))
                if len(segs) >= 5 and segs[4] == "artisans":
                    names.append(_CATEGORY_LABEL["artisans"])
                    names.extend(await _artisan_crumb_names(segs[5:]))
            elif segs[2] == "artisans":
                names.append(_CATEGORY_LABEL["artisans"])
                names.extend(await _artisan_crumb_names(segs[3:]))
    elif head == "users":
        names.append("Users")
        if len(segs) >= 2:
            u = await db.user.find_unique(where={"id": segs[1]})
            names.append(_seg(getattr(u, "name", None), segs[1]))
        if len(segs) >= 3:
            names.append(_CATEGORY_LABEL.get(segs[2], segs[2]))
    elif head == "media-types":
        names.append("Media types")
        if len(segs) >= 2:
            names.append(_CATEGORY_LABEL.get(segs[1], segs[1]))
    elif head == "by-design-workshop":
        names.append(_CATEGORY_LABEL["by-design-workshop"])
        if len(segs) >= 2:
            record = await db.designworkshop.find_unique(where={"id": segs[1]})
            names.append(_seg(getattr(record, "title", None), segs[1]))
        if len(segs) >= 3:
            names.append(_CATEGORY_LABEL.get(segs[2], segs[2]))
        if len(segs) >= 4:
            # The stage crumb is resolved from the REGISTRY, not the database: a stage key is the
            # registry's own name for a stage and there is no row anywhere that holds its title. The
            # number is padded exactly as the folder name is, so the crumb and the folder a person
            # clicked read the same.
            stage = next((s for s in dw.stages() if s.key == segs[3]), None)
            names.append(
                f"{stage.number:02d} {stage.title}" if stage is not None else _seg(segs[3], segs[3])
            )

    # Any unresolved tail segments (unknown shapes) fall back to the raw segment.
    while len(names) < len(segs):
        names.append(segs[len(names)])
    return names


async def _resolve_crumbs(norm: str) -> list[dict[str, str]]:
    segs = [s for s in norm.split("/") if s]
    crumbs = [{"name": "Repository", "path": ""}]
    names = await _crumb_names(segs)
    path = ""
    for seg, name in zip(segs, names):
        path = _join(path, seg)
        crumbs.append({"name": name, "path": path})
    return crumbs


# ---------------------------------------------------------------------------
# One level of the virtual tree. Each lister returns (entries, info): entries may
# carry _-prefixed internal fields consumed by the manifest walk and stripped from
# /tree; info is the record panel for record-folder levels (None elsewhere).
# ---------------------------------------------------------------------------

Level = tuple[list[dict[str, Any]], dict[str, Any] | None]


async def _list_level(path: str, scope: Scope) -> Level:
    segs = [s for s in path.split("/") if s]
    parent = "/".join(segs)

    if not segs:
        # The root is the taxonomy chooser. The hierarchy taxonomy is listed first and is
        # what the client opens by default.
        return [
            _folder(t["name"], t["path"], "taxonomy") for t in _taxonomies_for(scope)
        ], None

    head = segs[0]

    # THE ACCESS RULE, AT THE ONE PLACE EVERY LEVEL AND EVERY WALK PASSES THROUGH. ``/tree``,
    # ``/manifest`` and ``/report`` all funnel here or into ``_report_records``, so a caller who may
    # not read design-workshop data cannot reach it by typing the path — which is exactly what a
    # gate hung on the taxonomy LIST would have allowed. A 404 rather than a 403, matching what this
    # module answers for any path it does not serve: an account that may not read this taxonomy has
    # no business learning that it exists and holds records.
    if head == "by-design-workshop":
        if not scope.design_workshops:
            raise HTTPException(
                status_code=status.HTTP_404_NOT_FOUND, detail="Unknown data path"
            )
        return await _list_design_workshops_level(segs, parent, scope)

    # by-workshop / by-uploader / by-type are the current taxonomy roots; workshops /
    # users / media-types are the pre-taxonomy paths, still resolved so links saved
    # before the switcher existed keep working.
    if head in ("by-workshop", "workshops"):
        return await _list_workshops_level(segs, parent, scope)
    if head == "by-uploader":
        return await _list_uploader_level(segs, parent, scope)
    if head in ("by-type", "media-types"):
        return await _list_media_types_level(segs, parent, scope)
    if head == "users":
        return await _list_users_level(segs, parent, scope)

    raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Unknown data path")


async def _linked_artisans(ws: Any, scope: Scope) -> list[Any]:
    """Every artisan this workshop reaches, by any of the three routes the data actually uses.

    A workshop is joined to its artisans three different ways, because three different features
    wrote the link at three different times:

    1. ``WorkshopArtisan`` — the explicit "linked artisans" multi-select on the workshop form;
    2. ``Artisan.workshopId`` — the workshop dropdown that later went on every record form;
    3. ``WorkshopCraft`` -> ``Artisan.craftId`` — the workshop declares the crafts it covers, and an
       artisan practising one of those crafts was documented at it.

    Only route 1 used to count here, and on the live repository route 1 is EMPTY: the workshop has
    nine linked crafts and sixteen artisans hanging off those crafts, and not one ``WorkshopArtisan``
    row. That is why every craft folder in the browser opened onto nothing. Reaching an artisan
    through the craft is not a fallback — it is the relationship the data was entered with.

    Visibility is applied in the query, so an artisan the caller may not open never appears and
    never conjures a craft folder.
    """
    return await db.artisan.find_many(
        where=_and({"OR": workshop_artisan_reach(ws)}, scope.records),
        include={"craft": True},
        take=TAKE,
        order={"name": "asc"},
    )


def workshop_artisan_reach(ws: Any) -> list[dict[str, Any]]:
    """The three routes of :func:`_linked_artisans`, as Prisma ``OR`` terms on Artisan.

    ``ws`` must carry its ``artisans`` and ``crafts`` link rows. Exported because /export/dataset
    files the same artisans into the same folders and must not answer this question differently:
    the export used route 1 alone, so on the live repository — where route 1 is empty — every
    artisan's details.txt and own media were left out of the ZIP that the browser showed.
    """
    ors: list[dict[str, Any]] = [{"workshopId": ws.id}]
    linked_ids = [link.artisanId for link in ws.artisans or [] if getattr(link, "artisanId", None)]
    if linked_ids:
        ors.append({"id": {"in": linked_ids}})
    craft_ids = [link.craftId for link in ws.crafts or [] if getattr(link, "craftId", None)]
    if craft_ids:
        ors.append({"craftId": {"in": craft_ids}})
    return ors


def workshop_reaches_artisan(ws: Any, artisan: Any) -> bool:
    """Does this workshop reach this artisan? The in-memory twin of :func:`workshop_artisan_reach`,
    for the export, which already holds every artisan row and must not re-query per workshop.

    It EVALUATES the same OR terms rather than restating the three routes, so a fourth route added
    to the query is honoured here too instead of quietly splitting the two answers apart.
    """
    for term in workshop_artisan_reach(ws):
        for field, condition in term.items():
            value = getattr(artisan, field, None)
            if value is None:
                break
            if isinstance(condition, dict):
                if value not in condition["in"]:
                    break
            elif value != condition:
                break
        else:
            return True
    return False


def _craft_folder_entries(ws: Any, base: str, artisans: list[Any]) -> list[dict[str, Any]]:
    """One folder per craft reachable in this workshop: its directly linked crafts unioned with
    its artisans' crafts, plus a 'No craft' folder when any linked artisan has no craft.

    ``ws`` must be loaded with ``crafts->craft`` included; ``artisans`` are the workshop's linked
    artisans the caller may see (already visibility-filtered, each loaded with its ``craft``), so a
    craft folder is never conjured out of an artisan this caller cannot open.
    """
    crafts: dict[str, Any] = {}
    for link in ws.crafts or []:
        craft = getattr(link, "craft", None)
        if craft is not None:
            crafts.setdefault(craft.id, craft)
    no_craft = False
    for artisan in artisans:
        craft = getattr(artisan, "craft", None)
        if craft is not None:
            crafts.setdefault(craft.id, craft)
        elif not artisan.craftId:
            no_craft = True
    used: set[str] = set()
    entries = [
        _folder(_uniq(_seg(c.name, "Craft"), used), f"{base}/{c.id}", "craft")
        for c in crafts.values()
    ]
    if no_craft:
        entries.append(_folder(_uniq("No craft", used), f"{base}/{NO_CRAFT}"))
    return entries


async def _workshop_craft_artisans(wid: str, cid: str, scope: Scope) -> list[Any]:
    """The workshop's visible artisans practising ``cid`` (NO_CRAFT = the ones with no craft).

    Loads with ``_WS_CRAFTS_INCLUDE`` so :func:`_linked_artisans` can see the workshop's crafts and
    therefore reach artisans through them; loading only the ``artisans`` relation (as this used to)
    silently removed route 3 and returned an empty folder.
    """
    ws = await _require(
        db.workshop, wid, "Workshop", include=_WS_CRAFTS_INCLUDE, scope_where=scope.records
    )
    reachable = await _linked_artisans(ws, scope)
    if cid == NO_CRAFT:
        return [a for a in reachable if not a.craftId]
    return [a for a in reachable if a.craftId == cid]


_WS_CRAFTS_INCLUDE = {
    "artisans": {"include": {"artisan": {"include": {"craft": True}}}},
    "crafts": {"include": {"craft": True}},
}


async def _list_workshops_level(segs: list[str], parent: str, scope: Scope) -> Level:
    if len(segs) == 1:
        workshops = await db.workshop.find_many(
            where=scope.records, take=TAKE, order={"title": "asc"}
        )
        used: set[str] = set()
        return [
            _folder(_uniq(_seg(ws.title, "Workshop"), used), _join(parent, ws.id), "workshop")
            for ws in workshops
        ], None

    wid = segs[1]

    if len(segs) == 2:
        ws = await _require(
            db.workshop, wid, "Workshop", include=_WS_CRAFTS_INCLUDE, scope_where=scope.records
        )
        info = _workshop_info(ws)
        entries = _craft_folder_entries(ws, f"{parent}/crafts", await _linked_artisans(ws, scope))
        details = _text(parent, "details.txt", _info_text(info))
        if details:
            entries.append(details)
        # The workshop OWN media, in the workshop folder - not behind a "Miscellaneous" door.
        # Every level of this tree shows the same three things together: the folders below it, its
        # own fields as a table, and the files that belong to it. A folder whose files are one more
        # click away reads as a folder with no files. (`_misc` still resolves, for saved links.)
        entries.extend(
            _media_entries(
                parent,
                await _workshop_misc_media(wid, scope),
                record_type="Workshop",
                record_name=ws.title,
            )
        )
        return entries, info

    if segs[2] == "crafts":
        if len(segs) == 3:
            # The intermediate 'crafts' path — reachable from breadcrumbs — lists the same craft
            # folders as the workshop level, so every displayed crumb resolves.
            ws = await _require(
                db.workshop, wid, "Workshop", include=_WS_CRAFTS_INCLUDE, scope_where=scope.records
            )
            return _craft_folder_entries(ws, parent, await _linked_artisans(ws, scope)), None

        cid = segs[3]

        if len(segs) == 4:
            info = None
            if cid != NO_CRAFT:
                craft = await _require(db.craft, cid, "Craft")
                info = _craft_info(craft)
            artisans = await _workshop_craft_artisans(wid, cid, scope)
            used = set()
            entries = [
                _folder(
                    _uniq(_seg(a.name, "Artisan"), used), f"{parent}/artisans/{a.id}", "artisan"
                )
                for a in artisans
            ]
            details = _text(parent, "details.txt", _info_text(info))
            if details:
                entries.append(details)
            # Media captured against the craft itself (the craft form has its own capture field).
            if cid != NO_CRAFT:
                entries.extend(
                    _media_entries(
                        parent,
                        await _media(_record_media_where("craftId", cid, ["craft"]), scope),
                        record_type="Craft",
                        record_name=getattr(craft, "name", None),
                    )
                )
            return entries, info

        if len(segs) == 5 and segs[4] == "artisans":
            # Intermediate 'artisans' crumb path under a craft folder.
            artisans = await _workshop_craft_artisans(wid, cid, scope)
            used = set()
            return [
                _folder(_uniq(_seg(a.name, "Artisan"), used), f"{parent}/{a.id}", "artisan")
                for a in artisans
            ], None

        if len(segs) >= 6 and segs[4] == "artisans":
            # Below the artisan everything is craft-agnostic: reuse the artisan lister with the
            # segments remapped to the legacy shape (child paths still keep the craft-ful parent).
            remapped = [segs[0], wid, "artisans", *segs[5:]]
            return await _list_artisan_level(remapped, parent, scope)

        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Unknown data path")

    if len(segs) == 3 and segs[2] == "_misc":
        # No longer listed as a folder (the same files now render in the workshop folder itself),
        # but still resolvable so a link saved or bookmarked before that change does not 404.
        return _media_entries(parent, await _workshop_misc_media(wid, scope, named=True)), None

    if len(segs) == 3 and segs[2] == "artisans":
        # Legacy intermediate 'artisans' path (pre-craft-level tree): lists every linked artisan
        # so links saved before the craft level existed keep resolving.
        ws = await _require(
            db.workshop,
            wid,
            "Workshop",
            include={"artisans": {"include": {"artisan": True}}},
            scope_where=scope.records,
        )
        entries = []
        used = set()
        for artisan in await _linked_artisans(ws, scope):
            name = _uniq(_seg(artisan.name, "Artisan"), used)
            entries.append(_folder(name, f"{parent}/{artisan.id}", "artisan"))
        return entries, None

    if len(segs) >= 4 and segs[2] == "artisans":
        return await _list_artisan_level(segs, parent, scope)

    raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Unknown data path")


async def _list_artisan_level(segs: list[str], parent: str, scope: Scope) -> Level:
    wid, aid = segs[1], segs[3]

    if len(segs) == 4:
        # `location` is loaded because the info panel below prints the artisan's State and Pincode
        # from it; `_artisan_info` renders the full spec, not just the name shown in the tree.
        artisan = await _require(
            db.artisan,
            aid,
            "Artisan",
            include={"craft": True, "location": True},
            scope_where=scope.records,
        )
        info = _artisan_info(artisan)
        entries = [
            _folder("Products", f"{parent}/products"),
            _folder("Tools", f"{parent}/tools"),
            _folder("Questionnaire", f"{parent}/questionnaire"),
        ]
        details = _text(parent, "details.txt", _info_text(info))
        if details:
            entries.append(details)
        # The artisan own photographs and clips sit here with the sub-folders and the table, the
        # same shape as every other level. ("misc" still resolves for links saved before this.)
        entries.extend(
            _media_entries(
                parent,
                await _artisan_own_media(aid, scope),
                record_type="Artisan",
                record_name=artisan.name,
            )
        )
        return entries, info

    sub = segs[4]

    if sub == "products":
        return await _list_products_level(segs, parent, wid, aid, scope)

    if sub == "tools":
        if len(segs) == 5:
            tools = await db.tooldocumentation.find_many(
                where=_and(
                    {
                        "AND": [
                            {
                                "OR": [
                                    {"artisanId": aid},
                                    {"artisanLinks": {"some": {"artisanId": aid}}},
                                ]
                            },
                            {"OR": [{"workshopId": wid}, {"workshopId": None}]},
                        ]
                    },
                    scope.records,
                ),
                take=TAKE,
                order={"createdAt": "asc"},
            )
            used: set[str] = set()
            return [
                _folder(_uniq(_seg(t.toolkitName, "Tool"), used), f"{parent}/{t.id}", "tool")
                for t in tools
            ], None
        if len(segs) == 6:
            tool = await _require(db.tooldocumentation, segs[5], "Tool", scope_where=scope.records)
            info = _tool_info(tool)
            entries = []
            details = _text(parent, "details.txt", _info_text(info))
            if details:
                entries.append(details)
            media = await _media(_record_media_where("toolId", tool.id, ["tool"]), scope)
            entries.extend(
                _media_entries(parent, media, record_type="Tool", record_name=tool.toolkitName)
            )
            return entries, info

    if sub == "questionnaire":
        if len(segs) == 5:
            interviews = await db.questionnaireinterview.find_many(
                where=_and({"artisans": {"some": {"artisanId": aid}}}, scope.records),
                take=TAKE,
                order={"createdAt": "asc"},
                include={"artisans": {"include": {"artisan": True}}},
            )
            used = set()
            return [
                _folder(
                    _uniq(_seg(_interview_label(i), "Interview"), used),
                    f"{parent}/{i.id}",
                    "interview",
                )
                for i in interviews
            ], None
        if len(segs) == 6:
            interview = await _require(
                db.questionnaireinterview,
                segs[5],
                "Interview",
                include={
                    "responses": {"include": {"question": True}},
                    "artisans": {"include": {"artisan": True}},
                },
                scope_where=scope.records,
            )
            info = _interview_info(interview)
            entries = []
            answers = _text(parent, "answers.txt", _interview_answers(interview, info))
            if answers:
                entries.append(answers)
            # Per-question audio clips (and any other media) recorded for this interview.
            media = await _media(
                _record_media_where(
                    "questionnaireInterviewId",
                    interview.id,
                    ["questionnaire", "questionnaireinterview"],
                ),
                scope,
            )
            # Named after the artisan(s) the interview is WITH, not after the folder it is being
            # listed in: a group sitting shows up under each of its artisans, and a file that
            # changed its name depending on which door you came through would be unmatchable.
            interview_type, interview_name = interview_record(interview)
            entries.extend(
                _media_entries(
                    parent, media, record_type=interview_type, record_name=interview_name
                )
            )
            return entries, info

    if sub == "misc" and len(segs) == 5:
        # Same as the workshop "_misc": kept resolvable for older links, no longer a folder.
        return _media_entries(
            parent,
            await _artisan_own_media(aid, scope),
            record_type="Artisan",
            record_name=await _artisan_display_name(aid),
        ), None

    raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Unknown data path")


async def _list_products_level(
    segs: list[str], parent: str, wid: str, aid: str, scope: Scope
) -> Level:
    if len(segs) == 5:
        products = await db.productdocumentation.find_many(
            where=_and(
                {
                    "AND": [
                        {"artisanId": aid},
                        {"OR": [{"workshopId": wid}, {"workshopId": None}]},
                    ]
                },
                scope.records,
            ),
            take=TAKE,
            order={"createdAt": "asc"},
        )
        used: set[str] = set()
        return [
            _folder(_uniq(_seg(p.productName, "Product"), used), f"{parent}/{p.id}", "product")
            for p in products
        ], None

    pid = segs[5]

    if len(segs) == 6:
        product = await _require(db.productdocumentation, pid, "Product", scope_where=scope.records)
        info = _product_info(product)
        entries = []
        details = _text(parent, "details.txt", _info_text(info))
        if details:
            entries.append(details)
        media = await _media(_record_media_where("productId", pid, ["product"]), scope)
        entries.extend(
            _media_entries(parent, media, record_type="Product", record_name=product.productName)
        )
        if await db.process.count(where=_and({"productId": pid}, scope.records)) > 0:
            entries.append(_folder("Processes", f"{parent}/processes"))
        return entries, info

    if len(segs) == 7 and segs[6] == "processes":
        processes = await db.process.find_many(
            where=_and({"productId": pid}, scope.records), take=TAKE, order={"createdAt": "asc"}
        )
        used = set()
        return [
            _folder(_uniq(_seg(pr.name, "Process"), used), f"{parent}/{pr.id}", "process")
            for pr in processes
        ], None

    if len(segs) == 8 and segs[6] == "processes":
        process = await _require(
            db.process, segs[7], "Process", include={"steps": True}, scope_where=scope.records
        )
        info = _process_info(process)
        entries = []
        details = _text(parent, "details.txt", _info_text(info))
        if details:
            entries.append(details)
        media = await _media(
            {"AND": [{"linkedRecordType": "process"}, {"linkedRecordId": process.id}]}, scope
        )
        entries.extend(
            _media_entries(parent, media, record_type="Process", record_name=process.name)
        )
        used = set()
        for step in sorted(process.steps or [], key=lambda s: s.sortOrder):
            entries.append(
                _folder(_uniq(_seg(step.name, "Step"), used), f"{parent}/{step.id}", "process")
            )
        return entries, info

    if len(segs) == 9 and segs[6] == "processes":
        # ProcessStep carries no owner column of its own: its parent process decides who may see
        # it. For a scoped caller that means proving the process is visible AND that this step
        # really hangs off it; an unrestricted caller keeps the original single lookup.
        step_scope: dict[str, Any] | None = None
        if scope.records:
            await _require(db.process, segs[7], "Process", scope_where=scope.records)
            step_scope = {"processId": segs[7]}
        # The parent process rides along because a step's files are named "Process-<process>-Step-N
        # -<step>-..."; the step row carries the number and the name but not the process it is in.
        step = await _require(
            db.processstep,
            segs[8],
            "Process step",
            include={"process": True},
            scope_where=step_scope,
        )
        entries = []
        notes = _text(parent, "notes.txt", step.notes)
        if notes:
            entries.append(notes)
        media = await _media(
            {"AND": [{"linkedRecordType": "processstep"}, {"linkedRecordId": step.id}]}, scope
        )
        entries.extend(
            _media_entries(
                parent,
                media,
                record_type="Process",
                record_name=getattr(getattr(step, "process", None), "name", None),
                step_number=step.sortOrder,
                step_name=step.name,
            )
        )
        return entries, None

    raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Unknown data path")


async def _list_users_level(segs: list[str], parent: str, scope: Scope) -> Level:
    if len(segs) == 1:
        # Only uploaders who have media THIS caller can see are worth a folder.
        uploaders = await db.user.find_many(
            where={"media": {"some": _and({}, scope.media)}}, take=TAKE, order={"name": "asc"}
        )
        used: set[str] = set()
        return [
            _folder(_uniq(_seg(u.name, "User"), used), _join(parent, u.id), "user")
            for u in uploaders
        ], None

    uid = segs[1]

    if len(segs) == 2:
        # Unscoped on purpose: this only proves the account exists — the same directory-level fact
        # /users/directory serves to every authenticated user — and each folder under it lists
        # nothing but visibility-filtered media.
        await _require(db.user, uid, "User")
        # ``designworkshops`` is listed only for an account that may read that data — the branch
        # itself is refused below for anybody else, and a door that answers 404 is worse than no
        # door. It sits before ``misc`` because ``misc`` no longer holds these files: see the
        # comment above ``_TYPED_TAGS`` for the leak that closes.
        slugs = ["artisans", "products", "tools", "workshops", "questionnaire"]
        if scope.design_workshops:
            slugs.append("designworkshops")
        slugs.append("misc")
        return [_folder(_CATEGORY_LABEL[slug], f"{parent}/{slug}") for slug in slugs], None

    if len(segs) == 3 and segs[2] in _USER_TYPE_WHERE:
        if segs[2] == "designworkshops" and not scope.design_workshops:
            raise HTTPException(
                status_code=status.HTTP_404_NOT_FOUND, detail="Unknown data path"
            )
        media = await _media(
            {"AND": [{"uploadedById": uid}, _user_type_where(segs[2], scope)]}, scope, named=True
        )
        return _media_entries(parent, media), None

    raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Unknown data path")


def _transcript_entries(parent: str, media: list[Any]) -> list[dict[str, Any]]:
    """Transcripts rendered as their own text files, so the Transcripts folder actually
    contains transcripts rather than the audio they came from.

    Named off the same stem as the clip, so a transcript and the recording it came from still sort
    next to each other once both have been extracted from a zip."""
    used: set[str] = set()
    entries: list[dict[str, Any]] = []
    for position, m in enumerate(folder_order(media), start=1):
        text = (m.transcriptText or "").strip()
        if not text:
            continue
        name = unique_display_stem(
            m, used, extension=".transcript.md", position=position, fallback=m.id
        )
        entries.append(
            {
                "name": name,
                "path": _join(parent, name),
                "kind": "file",
                "originalFilename": m.originalFilename,
                "content": m.transcriptText,
                "mediaId": m.id,
            }
        )
    return entries


async def _list_media_types_level(segs: list[str], parent: str, scope: Scope) -> Level:
    """The by-type taxonomy: every file grouped purely by what kind of file it is.

    'transcripts' is not a MediaType — it is every media row that has transcript text,
    surfaced as .transcript.md documents in a folder of their own.
    """
    if len(segs) == 1:
        return [
            _folder(_CATEGORY_LABEL[slug], _join(parent, slug))
            for slug in ("images", "videos", "audios", "transcripts", "documents", "other")
        ], None
    if len(segs) == 2 and segs[1] == "transcripts":
        media = await _media({"NOT": {"transcriptText": None}}, scope, named=True)
        return _transcript_entries(parent, media), None
    if len(segs) == 2 and segs[1] in _MEDIA_TYPE_WHERE:
        media = await _media(_MEDIA_TYPE_WHERE[segs[1]], scope, named=True)
        return _media_entries(parent, media), None
    raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Unknown data path")



# ---------------------------------------------------------------------------
# The by-design-workshop taxonomy: the twenty-two-stage record, as folders.
#
#   by-design-workshop                             -> one folder per design workshop
#   by-design-workshop/<wid>                       -> details.txt, 'stages', the designer's own
#                                                     questions, and the workshop's loose media
#   by-design-workshop/<wid>/stages                -> one folder per stage that has answers
#   by-design-workshop/<wid>/stages/<stageKey>     -> one <Entity>.txt per entity with rows, plus
#                                                     the photographs those rows cite
#
# WHY THE STAGE IS A FOLDER AND THE ENTITY IS A FILE. The stage is what a designer, a report and a
# ministry all order the fortnight by, so it is the level a person navigates. The entity is the
# TABLE (see services/design_workshop_data.py's header for why a stage is not one), and a table is a
# document rather than a place — putting each entity in a folder of its own would mean four clicks
# to read one answer and forty-four empty folders on a workshop that has reached stage 8.
#
# ONLY STAGES WITH ANSWERS ARE LISTED, and the workshop folder says how many of the twenty-two those
# are. A tree that showed all twenty-two would be twenty-two doors of which most open on nothing;
# a tree that showed only the answered ones without saying so would be indistinguishable from a
# workshop that has no other stages. Rule 10: the count is on the details panel, in the folder.
# ---------------------------------------------------------------------------


def _dw_media_where(wid: str) -> dict[str, Any]:
    """Every file that belongs to one design workshop, by either reading.

    The tag pair is what the capture screens write for a stage photograph and for dictation; the
    ``designWorkshopId`` column is what the Miscellaneous Media form writes when a researcher files
    a loose upload under a workshop. A file may carry one, both or neither (see the column's note in
    schema.prisma), so both are asked and the union is the workshop's media.
    """
    return {
        "OR": [
            {
                "AND": [
                    {"linkedRecordType": {"in": _DESIGN_WORKSHOP_TAGS}},
                    {"linkedRecordId": wid},
                ]
            },
            {"designWorkshopId": wid},
        ]
    }


async def _dw_require(wid: str) -> Any:
    """One design workshop, or a 404.

    ``deletedAt: null``, like every other read of this table. A soft-deleted workshop is invisible
    to its own creator (``api/routes/design_workshops.py``'s header states the rule), so a research
    browser that listed it would be the one surface in the product that resurrects deleted work.

    NO ``scope.records`` FILTER, and that is not an omission. ``Scope.records`` narrows the legacy
    tables by ``createdById`` for an account holding ``canDownloadDataset`` without the rank behind
    it — and such an account does not reach this taxonomy at all (``scope.design_workshops`` is
    False for it, checked at :func:`_list_level`). Everybody who does reach it has empty row
    filters by construction, so a filter here would be a clause that can never fire, sitting where a
    later reader would take it for the access rule. The access rule is the flag.
    """
    record = await db.designworkshop.find_first(where={"id": wid, "deletedAt": None})
    if record is None:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND, detail="Design workshop not found"
        )
    return record


async def _dw_entries(wid: str) -> list[Any]:
    """One workshop's live stage rows, in the order the product itself reads them.

    ``deletedAt: null`` and ``ordinal`` ascending: the same order ``design_workshops.py`` applies,
    because ``ordinal`` is the single ordering input in this product and it is a hand-made
    arrangement. ``services/design_workshop_data.py`` deliberately does not re-sort what it is
    given, so the order this query applies is the order that reaches the file.
    """
    return await db.dwstageentry.find_many(
        where={"designWorkshopId": wid, "deletedAt": None},
        order=[{"ordinal": "asc"}, {"createdAt": "asc"}],
        take=TAKE,
    )


def _dw_info(record: Any, answered_stages: int, entry_count: int) -> dict[str, Any]:
    """The workshop's own fields as an info panel — the promoted columns and the coverage counts.

    THE PROMOTED COLUMNS AND NOTHING ELSE, exactly the set
    ``design_workshop_data.WORKSHOP_IDENTITY_COLUMNS`` carries onto every flattened row. They exist,
    per ``schema.prisma``'s own note above ``DesignWorkshop``, because they are "the axes a
    researcher actually filters and sorts on", so the panel and the table say the same thing about
    the same workshop rather than two overlapping things.

    THE TWO COUNTS ARE THE ANTI-SILENCE PROMISE. The folder lists only the stages that have answers;
    without "8 of 22 stages answered" on the panel beside it, a workshop that has reached stage 8
    and a workshop whose other fourteen stages failed to load look identical.
    """
    identity = dw.workshop_identity(record)
    fields = [
        {"label": column.label, "value": identity.get(column.key, "")}
        for column in dw.WORKSHOP_IDENTITY_COLUMNS
        if identity.get(column.key, "")
    ]
    fields.append(
        {
            "label": "Stages answered",
            "value": f"{answered_stages} of {len(dw.stages())}",
        }
    )
    fields.append({"label": "Rows recorded", "value": str(entry_count)})
    return {"title": str(getattr(record, "title", "") or "Design workshop"), "fields": fields}


def _dw_rows_text(title: str, columns: Sequence[Any], rows: list[dict[str, str]]) -> str:
    """One entity's rows as a readable text block: ``Label: value``, one row per paragraph.

    A .txt AND NOT A .csv, and the reason is that this file is read in the browser's preview pane
    beside the folder it came from. The CSV of the same rows is the report workbook, which is one
    click away and is the artefact built for a spreadsheet; a second CSV here would be the same
    table in two formats that can drift. Blank cells are dropped so a row of six answers is six
    lines rather than fifty-two, with forty-six of them empty.
    """
    blocks: list[str] = []
    for index, row in enumerate(rows, start=1):
        lines = [f"— {title} {index} —" if len(rows) > 1 else f"— {title} —"]
        for column in columns:
            value = (row.get(column.key) or "").strip()
            if not value:
                continue
            label = f"{column.label} ({column.unit})" if getattr(column, "unit", "") else column.label
            lines.append(f"{label}: {value}")
        blocks.append("\n".join(lines))
    return "\n\n".join(blocks)


def _dw_stage_folders(parent: str, entries: list[Any]) -> list[dict[str, Any]]:
    """One folder per stage that has at least one row, in stage order.

    Named "03 Workshop Plan, Participants & Opening" — the NUMBER FIRST and zero-padded, because the
    tree sorts its folders alphabetically (see ``data_tree``'s sort) and "Stage 10" sorts before
    "Stage 2" in every file browser in the world. The pad is what makes the alphabetical order and
    the fortnight's order the same order.
    """
    present: dict[str, tuple[int, str]] = {}
    for entry in entries:
        found = dw.entity_by_key(str(getattr(entry, "entityKey", "") or ""))
        if found is None:
            continue
        stage, _entity = found
        present[stage.key] = (stage.number, stage.title)
    used: set[str] = set()
    return [
        _folder(
            _uniq(_seg(f"{number:02d} {title}", f"Stage {number}"), used),
            _join(parent, key),
            "designWorkshopStage",
        )
        for key, (number, title) in sorted(present.items(), key=lambda item: item[1][0])
    ]


async def _list_design_workshops_level(segs: list[str], parent: str, scope: Scope) -> Level:
    """The by-design-workshop taxonomy — see the block comment above for the path shapes."""
    if len(segs) == 1:
        workshops = await db.designworkshop.find_many(
            where={"deletedAt": None}, take=TAKE, order={"title": "asc"}
        )
        used: set[str] = set()
        return [
            _folder(
                _uniq(_seg(record.title, "Design workshop"), used),
                _join(parent, record.id),
                "designWorkshop",
            )
            for record in workshops
        ], None

    wid = segs[1]

    if len(segs) == 2:
        # FOUR INDEPENDENT READS, ONE WAVE. None consumes another's output, and a cross-region
        # hop is ~750ms, so in series this level costs three seconds before a folder appears.
        record, entries, media, definition = await gather_reads(
            _dw_require(wid),
            _dw_entries(wid),
            _media(_dw_media_where(wid), scope),
            load_definition_or_empty(wid),
        )
        grouped, unknown = dw.flatten(record, entries, definition)
        stage_folders = _dw_stage_folders(f"{parent}/stages", entries)
        info = _dw_info(record, len(stage_folders), len(entries))
        entries_out: list[dict[str, Any]] = list(stage_folders)

        details = _text(parent, "details.txt", _info_text(info))
        if details:
            entries_out.append(details)

        # THE DESIGNER'S OWN QUESTIONS, AT THE WORKSHOP LEVEL, because they belong to no stage. The
        # definition lives per workshop in DwCustomSection/DwCustomField, so a tree driven only by
        # ``stages()`` would omit every question a designer wrote themselves — the silent-emptiness
        # class this repository keeps paying for.
        custom = grouped.get(dw.CUSTOM_ENTITY_KEY) or []
        if custom:
            custom_text = _text(
                parent,
                "designer-questions.txt",
                _dw_rows_text("Answers", dw.custom_columns(definition), custom),
            )
            if custom_text:
                entries_out.append(custom_text)

        # ROWS THIS BUILD CANNOT DESCRIBE ARE NAMED, NOT DROPPED. A handset one release ahead syncs
        # rows written against a newer registry; refusing to mention them would under-report the
        # corpus while looking complete.
        if unknown:
            note = "\n".join(
                f"{item.rows} row(s) under '{item.entity_key}' were written against a newer "
                "version of the form and cannot be shown by this server."
                for item in unknown
            )
            future = _text(parent, "not-shown.txt", note)
            if future:
                entries_out.append(future)

        # The workshop's own media: everything not cited by a stage row, named for the workshop.
        attributions = dw.media_attributions(record, entries)
        loose = [m for m in media if m.id not in attributions]
        entries_out.extend(
            _media_entries(
                parent, loose, record_type="Design workshop", record_name=record.title
            )
        )
        return entries_out, info

    if segs[2] != "stages":
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Unknown data path")

    if len(segs) == 3:
        # The intermediate 'stages' crumb, reachable from a breadcrumb, listing the same folders as
        # the workshop level so every displayed crumb resolves.
        entries = await _dw_entries(wid)
        return _dw_stage_folders(parent, entries), None

    stage_key = segs[3]
    if len(segs) != 4:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Unknown data path")

    stage = next((s for s in dw.stages() if s.key == stage_key), None)
    if stage is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Unknown data path")

    record, entries, media = await gather_reads(
        _dw_require(wid), _dw_entries(wid), _media(_dw_media_where(wid), scope)
    )
    grouped, _unknown = dw.flatten(record, entries)
    out: list[dict[str, Any]] = []
    for entity in stage.entities:
        rows = grouped.get(entity.key) or []
        if not rows:
            continue
        text = _text(
            parent,
            f"{_seg(entity.title, entity.key)}.txt",
            _dw_rows_text(entity.title, dw.entity_columns(entity), rows),
        )
        if text:
            out.append(text)

    # THE PHOTOGRAPHS THIS STAGE'S ROWS CITE, under the stage that cites them. This is the media
    # identity fix at the tree level: the same files used to land anonymously in Miscellaneous.
    attributions = dw.media_attributions(record, entries)
    mine = [
        m
        for m in media
        if (attributions.get(m.id) is not None and attributions[m.id].stage_key == stage_key)
    ]
    used: set[str] = set()
    for position, m in enumerate(folder_order(mine), start=1):
        attribution = attributions[m.id]
        name = unique_display_filename(
            m,
            used,
            record_type="Stage",
            record_name=f"{attribution.stage_number:02d} {attribution.field_label}",
            position=position,
            fallback=m.id,
        )
        out.append(
            {
                "name": name,
                "path": _join(parent, name),
                "kind": "file",
                "originalFilename": m.originalFilename,
                "mediaType": str(_ev(m.mediaType)),
                "mediaId": m.id,
                "url": m.url,
                "sizeBytes": int(m.sizeBytes) if m.sizeBytes is not None else None,
                "transcriptAvailable": bool((m.transcriptText or "").strip()),
                "_transcriptText": m.transcriptText,
            }
        )
    return out, {
        "title": f"Stage {stage.number}: {stage.title}",
        "fields": [
            {"label": "Workshop", "value": str(getattr(record, "title", "") or "")},
            {"label": "Tables with rows", "value": str(len(out) - len(mine))},
            {"label": "Files", "value": str(len(mine))},
        ],
    }


# ---------------------------------------------------------------------------
# The by-uploader taxonomy: workshop -> the researcher who uploaded -> what they
# recorded there. Each record folder carries the fields they filled in (as a table)
# and their media split by type.
# ---------------------------------------------------------------------------

# Record types a user's contribution is broken down into, with the delegate and the
# workshop-scoping predicate for each.
_UPLOADER_BRANCHES: dict[str, str] = {
    "artisans": "Artisans",
    "products": "Products",
    "tools": "Tools",
    "questionnaire": "Questionnaire",
    "media": "Media",
}


async def _uploader_records(branch: str, wid: str, uid: str, scope: Scope) -> tuple[str, list[Any]]:
    """(record kind, records) this user created of one type within one workshop."""
    if branch == "artisans":
        return "artisan", await db.artisan.find_many(
            where=_and(
                {"AND": [{"createdById": uid}, {"workshops": {"some": {"workshopId": wid}}}]},
                scope.records,
            ),
            take=TAKE,
            order={"createdAt": "asc"},
            include=_ARTISAN_INCLUDE,
        )
    if branch == "products":
        return "product", await db.productdocumentation.find_many(
            where=_and({"AND": [{"createdById": uid}, {"workshopId": wid}]}, scope.records),
            take=TAKE,
            order={"createdAt": "asc"},
            include=_PRODUCT_INCLUDE,
        )
    if branch == "tools":
        return "tool", await db.tooldocumentation.find_many(
            where=_and({"AND": [{"createdById": uid}, {"workshopId": wid}]}, scope.records),
            take=TAKE,
            order={"createdAt": "asc"},
            include=_TOOL_INCLUDE,
        )
    if branch == "questionnaire":
        return "interview", await db.questionnaireinterview.find_many(
            where=_and(
                {
                    "AND": [
                        {"createdById": uid},
                        {
                            "artisans": {
                                "some": {"artisan": {"workshops": {"some": {"workshopId": wid}}}}
                            }
                        },
                    ]
                },
                scope.records,
            ),
            take=TAKE,
            order={"createdAt": "asc"},
            include=_INTERVIEW_INCLUDE,
        )
    return "", []


async def _list_uploader_level(segs: list[str], parent: str, scope: Scope) -> Level:
    # by-uploader -> one folder per workshop
    if len(segs) == 1:
        workshops = await db.workshop.find_many(
            where=scope.records, take=TAKE, order={"title": "asc"}
        )
        used: set[str] = set()
        return [
            _folder(_uniq(_seg(ws.title, "Workshop"), used), _join(parent, ws.id), "workshop")
            for ws in workshops
        ], None

    wid = segs[1]

    # by-uploader/<wid> -> the people who put data into this workshop
    if len(segs) == 2:
        ws = await _require(
            db.workshop, wid, "Workshop", include=_WS_CRAFTS_INCLUDE, scope_where=scope.records
        )
        uploaders = await db.user.find_many(
            where={"media": {"some": _and({"workshopId": wid}, scope.media)}},
            take=TAKE,
            order={"name": "asc"},
        )
        # Someone can author records without ever uploading a file, so union the two sets.
        authors = await db.user.find_many(
            where={
                "OR": [
                    {"products": {"some": _and({"workshopId": wid}, scope.records)}},
                    {"tools": {"some": _and({"workshopId": wid}, scope.records)}},
                ]
            },
            take=TAKE,
            order={"name": "asc"},
        )
        seen: dict[str, Any] = {}
        for u in [*uploaders, *authors]:
            seen.setdefault(u.id, u)
        used: set[str] = set()
        entries = [
            _folder(_uniq(_seg(u.name, "User"), used), _join(parent, u.id), "user")
            for u in sorted(seen.values(), key=lambda u: (u.name or "").lower())
        ]
        return entries, _workshop_info(ws)

    uid = segs[2]

    # by-uploader/<wid>/<uid> -> what this person recorded here. Unscoped like the legacy users
    # level: it resolves an account's directory card, and every branch below it is filtered.
    if len(segs) == 3:
        user = await _require(db.user, uid, "User")
        entries = [
            _folder(label, _join(parent, slug)) for slug, label in _UPLOADER_BRANCHES.items()
        ]
        return entries, {
            "title": (user.name or "").strip() or "User",
            "fields": [
                f
                for f in [
                    {"label": "Email", "value": _cell(user.email)},
                    {"label": "Role", "value": _cell(_enum_label(user.role))},
                ]
                if f["value"]
            ],
        }

    branch = segs[3]

    if branch == "media":
        # .../media -> the same by-type split, scoped to this uploader and workshop
        if len(segs) == 4:
            return [
                _folder(_CATEGORY_LABEL[slug], _join(parent, slug))
                for slug in ("images", "videos", "audios", "transcripts", "documents", "other")
            ], None
        if len(segs) == 5:
            owned = [{"uploadedById": uid}, {"workshopId": wid}]
            if segs[4] == "transcripts":
                media = await _media(
                    {"AND": [*owned, {"NOT": {"transcriptText": None}}]}, scope, named=True
                )
                return _transcript_entries(parent, media), None
            if segs[4] in _MEDIA_TYPE_WHERE:
                media = await _media(
                    {"AND": [*owned, _MEDIA_TYPE_WHERE[segs[4]]]}, scope, named=True
                )
                return _media_entries(parent, media), None
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Unknown data path")

    if branch in _UPLOADER_BRANCHES and len(segs) == 4:
        kind, records = await _uploader_records(branch, wid, uid, scope)
        if not kind:
            raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Unknown data path")
        spec = SPECS[kind]
        used: set[str] = set()
        entries: list[dict[str, Any]] = []
        for record in records:
            name = _uniq(_seg(spec.title(record), spec.label), used)
            details = _text(parent, f"{name}.txt", _info_text(info_panel(kind, record)))
            if details:
                entries.append(details)
        # The folder itself carries the tabular view of every record listed in it.
        return entries, None

    raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Unknown data path")


# ---------------------------------------------------------------------------
# Endpoints
# ---------------------------------------------------------------------------


# ---------------------------------------------------------------------------
# Reverse lookup: record -> its folder.
#
# Search finds a record by name; this says where that record LIVES, so a hit can drop the
# reader into the right folder instead of a dead end. The web View Data page ("Show in
# folders") and the Android data screen both call it. Everything below reuses the same
# reachability rule the tree itself walks (:func:`_linked_artisans`), so a path this returns
# is always a path :func:`data_tree` can open.
# ---------------------------------------------------------------------------


async def _workshop_for_artisan(artisan: Any, scope: Scope) -> Any | None:
    """The workshop whose folder holds this artisan, by the same three routes the tree uses."""
    if artisan.workshopId:
        found = await db.workshop.find_first(where=_and({"id": artisan.workshopId}, scope.records))
        if found:
            return found
    link = await db.workshopartisan.find_first(
        where={"artisanId": artisan.id}, include={"workshop": True}
    )
    if (
        link is not None
        and getattr(link, "workshop", None) is not None
        and (
            not scope.records
            or await db.workshop.find_first(where=_and({"id": link.workshopId}, scope.records))
        )
    ):
        return link.workshop
    if artisan.craftId:
        craft_link = await db.workshopcraft.find_first(
            where={"craftId": artisan.craftId}, include={"workshop": True}
        )
        if (
            craft_link is not None
            and getattr(craft_link, "workshop", None) is not None
            and (
                not scope.records
                or await db.workshop.find_first(
                    where=_and({"id": craft_link.workshopId}, scope.records)
                )
            )
        ):
            return craft_link.workshop
    return None


async def _artisan_path(aid: str, scope: Scope) -> str | None:
    artisan = await db.artisan.find_first(where=_and({"id": aid}, scope.records))
    if artisan is None:
        return None
    workshop = await _workshop_for_artisan(artisan, scope)
    if workshop is None:
        return None
    craft = artisan.craftId or NO_CRAFT
    return f"by-workshop/{workshop.id}/crafts/{craft}/artisans/{artisan.id}"


async def _locate_path(record_type: str, record_id: str, scope: Scope) -> str | None:
    kind = (record_type or "").strip().lower()

    if kind == "workshop":
        found = await db.workshop.find_first(where=_and({"id": record_id}, scope.records))
        return f"by-workshop/{found.id}" if found else None

    if kind == "artisan":
        return await _artisan_path(record_id, scope)

    if kind == "craft":
        link = await db.workshopcraft.find_first(where={"craftId": record_id})
        if link is None:
            return None
        if scope.records and not await db.workshop.find_first(
            where=_and({"id": link.workshopId}, scope.records)
        ):
            return None
        return f"by-workshop/{link.workshopId}/crafts/{record_id}"

    if kind in {"product", "tool"}:
        delegate = db.productdocumentation if kind == "product" else db.tooldocumentation
        record = await delegate.find_first(where=_and({"id": record_id}, scope.records))
        if record is None:
            return None
        owner = record.artisanId
        if owner is None and kind == "tool":
            link = await db.toolartisan.find_first(where={"toolId": record_id})
            owner = link.artisanId if link else None
        if owner is None:
            return None
        base = await _artisan_path(owner, scope)
        return f"{base}/{kind}s/{record_id}" if base else None

    if kind == "process":
        process = await db.process.find_first(where=_and({"id": record_id}, scope.records))
        if process is None or not process.productId:
            return None
        product_path = await _locate_path("product", process.productId, scope)
        return f"{product_path}/processes/{record_id}" if product_path else None

    if kind in {"interview", "questionnaire", "questionnaireinterview"}:
        # `questionnaireinterviewartisan` / `interviewId` — the model is
        # QuestionnaireInterviewArtisan and its column is `interviewId` (schema.prisma:836). The
        # shorter names I first wrote do not exist, and because a delegate is resolved by attribute
        # access this failed at RUNTIME with an AttributeError, not at import: every "Show in
        # folders" on a questionnaire recording returned a 500. Nothing caught it because the media
        # branch below tries the interview owner FIRST, so no other owner was ever reached, and my
        # own smoke test only ever asked for an artisan.
        link = await db.questionnaireinterviewartisan.find_first(where={"interviewId": record_id})
        if link is None:
            return None
        base = await _artisan_path(link.artisanId, scope)
        return f"{base}/questionnaire/{record_id}" if base else None

    if kind in {"designworkshop", "design_workshop"}:
        # "Show in folders" on a design & prototype workshop. Refused outright for an account that
        # may not read the taxonomy — returning the path would name a folder they cannot open, which
        # is a worse answer than "nothing files this yet" because it looks like a broken link
        # rather than a rule.
        if not scope.design_workshops:
            return None
        found = await db.designworkshop.find_first(where={"id": record_id, "deletedAt": None})
        return f"by-design-workshop/{found.id}" if found else None

    if kind in {"media", "mediafile"}:
        media = await db.mediafile.find_first(where=_and({"id": record_id}, scope.media))
        if media is None:
            return None
        # A DESIGN-WORKSHOP FILE IS FILED UNDER ITS STAGE, AND IT IS TRIED FIRST. This is the media
        # identity fix at the "reveal this file" level: before it, such a file matched none of the
        # six owners below and fell through to the by-type bucket — "Images", the folder holding
        # every photograph in the repository, which locates nothing. Both readings are honoured for
        # the reason ``_dw_media_where`` states, and the STAGE is resolved from the workshop's own
        # rows because no column on ``MediaFile`` carries it.
        dw_id = getattr(media, "designWorkshopId", None) or (
            media.linkedRecordId
            if (media.linkedRecordType or "") in _DESIGN_WORKSHOP_TAGS
            else None
        )
        if dw_id and scope.design_workshops:
            record = await db.designworkshop.find_first(where={"id": dw_id, "deletedAt": None})
            if record is not None:
                base = f"by-design-workshop/{record.id}"
                attribution = dw.media_attributions(record, await _dw_entries(record.id)).get(
                    media.id
                )
                # No attribution means no stage row cites it — a miscellaneous upload filed under
                # the workshop from the dropdown. The workshop folder is where those are listed, so
                # that is where it is revealed.
                return f"{base}/stages/{attribution.stage_key}" if attribution else base

        # Deepest owner wins: a clip on a product belongs in the product folder, not the artisan's.
        for owner_kind, owner_id in (
            ("interview", media.questionnaireInterviewId),
            ("product", media.productId),
            ("tool", media.toolId),
            ("artisan", media.artisanId),
            ("craft", media.craftId),
            ("workshop", media.workshopId),
        ):
            if owner_id:
                found = await _locate_path(owner_kind, owner_id, scope)
                if found:
                    return found
        # No owner at all: it can still be reached in the by-type taxonomy.
        bucket = _TYPE_TOKEN.get(str(_ev(media.mediaType)).upper())
        return f"by-type/{bucket}" if bucket else "by-type"

    return None


@router.get("/locate")
async def data_locate(
    type: str = Query(
        ...,
        description=(
            "workshop | craft | artisan | product | tool | process | interview | media | "
            "designWorkshop"
        ),
    ),
    id: str = Query(..., min_length=1),
    current_user: Any = Depends(require_dataset_downloader),
) -> dict[str, str | None]:
    """The tree path that holds this record, or ``{"path": null}`` when nothing files it yet.

    A null is not an error: a product whose artisan has never been attached to a workshop genuinely
    has no folder, and the caller should say so rather than open the wrong one.
    """
    scope = await _scope_for(current_user)
    return {"path": await _locate_path(type, id, scope)}


@router.get("/tree")
async def data_tree(
    path: str = "", current_user: Any = Depends(require_dataset_downloader)
) -> dict[str, Any]:
    """One level of the virtual data tree (lazy: only this level's queries run).

    Response: {path, crumbs:[{name,path}], entries:[...], info:{title,fields}|null, truncated}.
    Crumbs cover every ancestor including the requested path itself, with clean names; info is
    populated on record folders (workshop/artisan/product/tool/process/interview). Everything
    listed is filtered by the caller's row visibility (see :class:`Scope`).
    """
    scope = await _scope_for(current_user)
    norm = _norm(path)
    entries, info = await _list_level(norm, scope)
    crumbs = await _resolve_crumbs(norm)
    public = [{k: v for k, v in e.items() if not k.startswith("_")} for e in entries]
    public.sort(key=lambda e: (0 if e["kind"] == "folder" else 1, e["name"].lower()))
    # A listing that hits the per-level cap likely has more rows than shown.
    return {
        "path": norm,
        "crumbs": crumbs,
        "entries": public,
        "info": info,
        "truncated": len(entries) >= TAKE,
        # The switcher is served with every level so the client always knows which
        # taxonomy it is inside and can offer the others without a second call. Filtered by the
        # caller's own capabilities — see :func:`_taxonomies_for`.
        "taxonomies": _taxonomies_for(scope),
        "taxonomy": _taxonomy_of(norm),
        # WHETHER THE DESIGN-WORKSHOP TABLES ON THIS SCREEN MAY BE TAKEN AWAY, said once per level
        # so the client never has to re-derive a permission from a role. A professor reads them here
        # and may not export them; the page has to say that where it applies rather than offering a
        # button that answers 403. Absent the flag the web would either hide a download the account
        # has (wrong for an admin) or offer one it does not (wrong for a professor).
        "designWorkshopsVisible": scope.design_workshops,
        "designWorkshopsDownloadable": scope.design_workshop_downloads,
    }


async def _walk(
    path: str,
    rel: str,
    include: set[str] | None,
    files: list[dict[str, Any]],
    depth: int,
    seen_media: set[str],
    state: dict[str, bool],
    scope: Scope,
) -> None:
    if depth > MAX_WALK_DEPTH or len(files) >= MAX_MANIFEST_FILES:
        state["truncated"] = True
        return
    try:
        # DB access capped by the semaphore (never held across recursion) so sibling subtrees
        # can walk concurrently without exhausting the connection pool.
        async with _WALK_SEM:
            entries, _ = await _list_level(path, scope)
    except HTTPException:
        return  # a record vanished mid-walk; skip that branch rather than failing the manifest
    if len(entries) >= TAKE:
        state["truncated"] = True
    # One folder's leaf names, and only this folder's: the ZIP writes these entries side by side, so
    # two of them may not agree, while the identical name a sibling folder holds is no clash at all.
    used_names: set[str] = set()

    def emit(entry: dict[str, Any], stem: str, extension: str) -> None:
        """Add one file to the manifest under a leaf nothing else in this folder answers to.

        The stem and the extension arrive apart because the number belongs between them, and the
        extension is not always the last dot: a transcript is a ``.transcript.md``, and splitting it
        off blindly would number the file ``…transcript-2.md``.
        """
        if len(files) < MAX_MANIFEST_FILES:
            entry["path"] = _join(rel, unique_name(stem, extension, used_names))
            files.append(entry)
        else:
            state["truncated"] = True

    child_walks = []
    for e in entries:
        if e["kind"] == "folder":
            child_walks.append(
                _walk(
                    e["path"],
                    _join(rel, e["name"]),
                    include,
                    files,
                    depth + 1,
                    seen_media,
                    state,
                    scope,
                )
            )
            continue
        if "content" in e:
            if include is None or "text" in include:
                entry = {"content": e["content"]}
                if e.get("originalFilename"):
                    entry["originalFilename"] = e["originalFilename"]
                emit(entry, *_split_leaf(e["name"]))
            continue
        # The three top-level views (workshops/users/media-types) overlap; each media object is
        # zipped once, at its first occurrence.
        if e["mediaId"] in seen_media:
            continue
        seen_media.add(e["mediaId"])
        media_type = e.get("mediaType") or "OTHER"
        token = _TYPE_TOKEN.get(media_type, "other")
        name = e["name"]
        stem, extension = _split_leaf(name)
        # The zip entry is named for what the file IS; the name it was uploaded under travels with
        # it so a researcher can still line an extracted file up against their own copy.
        original = e.get("originalFilename")
        if include is None or token in include:
            if media_type == "AUDIO":
                # Client fetches the AAC/mp4 conversion via /data/media/{id}/download?format=mp4,
                # falling back to the original object URL when conversion fails.
                emit(
                    {
                        "url": e.get("url"),
                        "originalPath": _join(rel, name),
                        "originalFilename": original,
                        "mediaId": e["mediaId"],
                        "mediaType": media_type,
                        "convertToMp4": True,
                    },
                    stem,
                    ".mp4",
                )
            else:
                emit(
                    {
                        "url": e.get("url"),
                        "originalFilename": original,
                        "mediaId": e["mediaId"],
                        "mediaType": media_type,
                    },
                    stem,
                    extension,
                )
        if e.get("transcriptAvailable") and (include is None or "transcripts" in include):
            emit(
                {
                    "content": e.get("_transcriptText") or "",
                    "originalFilename": original,
                    "mediaId": e["mediaId"],
                },
                stem,
                ".transcript.md",
            )
    if child_walks:
        await asyncio.gather(*child_walks)


# ---------------------------------------------------------------------------
# Serving a manifest as NDJSON — shared with /export/dataset.
#
# WHY THIS EXISTS. Both manifest endpoints answered with ONE JSON object holding every entry, and
# the entries carry inline text: a ``details.txt`` body per record, an ``answers.txt`` per
# questionnaire, and — with ``include=transcripts`` or no filter at all — ``_transcriptText``, the
# FULL transcript of every audio row in the subtree. ``MAX_MANIFEST_FILES = 20000`` here and
# ``MEDIA_TAKE = 20000`` + 6x``EXPORT_TAKE = 5000`` in export.py bound the entry COUNT and NOTHING
# bounds the byte size, so ``docs/SCALABILITY.md`` already measures this at 476 kB today and models
# ~48 MB at 100x the media.
#
# ON THE HANDSET THAT SINGLE BODY IS THE WHOLE FAILURE. ``WorkshopRepositoryApi.datasetManifest()``
# and ``.dataManifest()`` were typed Retrofit calls returning a fully-materialised ``@Serializable``
# class with no ``@Streaming``, so the response went through Retrofit's kotlinx-serialization
# converter. That converter is ``Serializer.FromString``: its ``fromResponseBody`` calls
# ``okhttp3.ResponseBody.string()`` and then ``decodeFromString`` (verified against the bytecode of
# retrofit2-kotlinx-serialization-converter 1.0.0, not from memory). ``string()`` bottoms out in
# okio's ``Buffer.readByteArray(byteCount)``, which is ONE contiguous ``ByteArray`` of the whole
# body, immediately copied into one contiguous ``String``. A 48 MB manifest therefore asks Android's
# allocator for a single 48 MB array on a heap that is also holding Compose, and the app dies with
# ``java.lang.OutOfMemoryError: Failed to allocate a 48000000 byte allocation``.
# ``android:largeHeap="true"`` is already set, so that mitigation is spent — and it would not have
# helped anyway: a large enough CONTIGUOUS allocation fails on a fragmented heap however much total
# free memory is reported.
#
# NDJSON RATHER THAN A JSON ARRAY, for the reason ``datasets.py`` already gives one module over: an
# array must be closed before a parser can begin, so a client cannot consume it incrementally and a
# truncated one yields nothing at all. One entry per line means the client decodes one entry at a
# time and never holds more than the longest single line.
#
# THE DEFAULT SHAPE IS UNCHANGED, DELIBERATELY. ``?stream=1`` is opt-in because the browser clients
# (frontend/app/(protected)/data/page.tsx, sharing/page.tsx) and every Android build already
# installed in the field read the JSON object, and there is no way to make them all upgrade at once.
# Do not "tidy" this by making NDJSON the default: that turns a deployment into a fleet-wide
# download outage on handsets nobody in this repo can reach.
# ---------------------------------------------------------------------------

MANIFEST_NDJSON_MEDIA_TYPE = "application/x-ndjson"
# The counts and the truncation flag travel as HEADERS because in NDJSON there is nowhere else to
# put them: they must arrive BEFORE the first entry so the client can show real progress from the
# first file, and a trailing summary line would be lost exactly when it matters most (a dropped
# connection). ``X-Dataset-Total`` is the name ``datasets.py::_stream_headers`` already uses for
# "how many things are in this body"; the two must not disagree about that word.
MANIFEST_TOTAL_HEADER = "X-Dataset-Total"
MANIFEST_MEDIA_HEADER = "X-Dataset-Media"
MANIFEST_TRUNCATED_HEADER = "X-Dataset-Truncated"
# THE FOURTH HEADER IS THIS REPOSITORY'S, NOT A COPY OF ANYTHING. ``/export/dataset`` here answers
# ``{files, totalFiles, totalMedia, skippedMedia, truncated}`` — five keys, not four — because a
# media row that could not be addressed at all is counted separately from a capped table (see
# export.py's closing comment on why ``skippedMedia`` is OR-ed into ``truncated`` rather than
# reported alone). Drop this header and the streamed path silently loses the one number that tells a
# researcher WHICH kind of incomplete their archive is. ``/data/manifest`` has no such concept and
# sends 0; a reader must therefore treat 0 and absent alike.
MANIFEST_SKIPPED_HEADER = "X-Dataset-Skipped"
# Lines per yield. Matches ``datasets.py``'s ``STREAM_BATCH``: one ASGI send per entry would spend
# more time in the protocol than in the encode on a 20,000-entry manifest.
_MANIFEST_STREAM_CHUNK = 200


def manifest_ndjson_response(
    files: list[dict[str, Any]],
    total_media: int,
    truncated: bool,
    filename: str,
    skipped_media: int = 0,
) -> StreamingResponse:
    """Serve an already-built manifest as newline-delimited JSON, one entry per line.

    This does NOT make the SERVER's manifest build incremental — both callers still assemble the
    whole ``files`` list before they get here, and doing otherwise means restructuring two recursive
    builders that de-duplicate paths against sets (``used_dirs``/``used``) they can only fill by
    walking everything first. What it does remove is the SECOND copy: ``JSONResponse`` would encode
    that list into one ~48 MB ``bytes`` object held beside the list itself while the socket drains.

    The entries are dropped from ``files`` as they are encoded (``files[i] = None``) for the same
    reason — the inline ``content`` strings are most of the manifest's weight, and releasing each one
    at the point it becomes bytes keeps the peak at roughly one copy instead of two. The list is the
    caller's, but the caller has already returned by the time this generator runs, so nothing else
    can observe the holes. Do not remove the ``= None`` as a tidy-up: on a single-worker t3.micro the
    doubled peak is the difference this endpoint's caps exist to avoid.
    """
    total_files = len(files)

    async def body() -> AsyncIterator[bytes]:
        for start in range(0, total_files, _MANIFEST_STREAM_CHUNK):
            lines: list[str] = []
            for index in range(start, min(start + _MANIFEST_STREAM_CHUNK, total_files)):
                entry = files[index]
                files[index] = None  # type: ignore[call-overload]  # see the docstring
                # ``ensure_ascii=False`` so a Devanagari transcript is not tripled in size on the
                # wire. ``json.dumps`` escapes real newlines inside strings as ``\n``, which is what
                # makes "one entry per line" hold for a multi-line details.txt body — break that and
                # the client reads one entry as several, most unparseable, and hands back a short
                # archive with no error raised anywhere in the system.
                lines.append(json.dumps(entry, ensure_ascii=False))
            yield ("\n".join(lines) + "\n").encode("utf-8")

    return StreamingResponse(
        body(),
        media_type=MANIFEST_NDJSON_MEDIA_TYPE,
        headers={
            MANIFEST_TOTAL_HEADER: str(total_files),
            MANIFEST_MEDIA_HEADER: str(total_media),
            # Spelled "true"/"false" rather than 1/0 so a reader cannot mistake it for a count.
            MANIFEST_TRUNCATED_HEADER: "true" if truncated else "false",
            MANIFEST_SKIPPED_HEADER: str(skipped_media),
            "Content-Disposition": f'attachment; filename="{filename}"',
        },
    )


@router.get("/manifest", response_model=None)
async def data_manifest(
    path: str = "",
    include: str | None = None,
    stream: bool = False,
    current_user: Any = Depends(require_dataset_downloader),
) -> dict[str, Any] | StreamingResponse:
    """Flattened manifest of the subtree below ``path`` — same shape as /export/dataset
    ({files:[{path,url?,content?,mediaId?,mediaType?}], totalFiles, totalMedia, truncated});
    the client downloads/zips client-side. ``include`` filters entry kinds; omitted = everything.
    The walk reuses the /tree listers, so it carries the same row visibility.

    ``stream=1`` answers the same entries as NDJSON with the counts in headers instead of a wrapper
    object — see :func:`manifest_ndjson_response` for the allocation failure it exists to stop, and
    for why it is opt-in rather than the default.
    """
    include_set: set[str] | None = None
    if include is not None and include.strip():
        include_set = {t.strip().lower() for t in include.split(",") if t.strip()}
    # ``for_download()`` AND NOT THE PLAIN SCOPE, because this route is the zip. Everything the walk
    # lists is a file the browser then fetches and writes to disk, so on this route viewing IS
    # exporting — and design-workshop stage data may be read on screen by a professor and taken out
    # only by an admin (``deps.can_export_design_workshop_data``). A professor asking for a manifest
    # therefore gets exactly the archive they got before this change: the seven legacy tables, whole.
    scope = (await _scope_for(current_user)).for_download()
    norm = _norm(path)
    files: list[dict[str, Any]] = []
    state = {"truncated": False}
    await _walk(norm, "", include_set, files, 0, set(), state, scope)
    total_media = sum(1 for f in files if f.get("mediaId") and f.get("content") is None)
    if stream:
        return manifest_ndjson_response(
            files, total_media, bool(state["truncated"]), "manifest.ndjson"
        )
    return {
        "files": files,
        "totalFiles": len(files),
        "totalMedia": total_media,
        "truncated": state["truncated"],
    }


# ---------------------------------------------------------------------------
# Relational report (/data/report): the subtree at a path flattened into linked
# sheets — one per record type — served as JSON or a styled .xlsx workbook.
# ---------------------------------------------------------------------------

# Sheet colours now come from the shared field registry (services/record_fields.py),
# so a record type is the same colour in the workbook tab, the web pill and the tree icon.

_ARTISAN_INCLUDE = {
    "craft": True,
    "createdBy": True,
    "workshops": {"include": {"workshop": True}},
    # The artisan spec prints State and Pincode off this relation; without it both cells fall back
    # to the legacy extraMetadata and read blank for every record entered since they became columns.
    "location": True,
}
_PRODUCT_INCLUDE = {"workshop": True, "createdBy": True}
# product->workshop is nested so a process row can resolve its workshop placement for the
# hierarchy columns without a second query.
_PROCESS_INCLUDE = {
    "steps": True,
    "product": {"include": {"workshop": True}},
    "createdBy": True,
}
_TOOL_INCLUDE = {"workshop": True, "createdBy": True}
_INTERVIEW_INCLUDE = {
    "artisans": {"include": {"artisan": True}},
    "responses": {"include": {"question": True}},
    "createdBy": True,
}
_REPORT_MEDIA_INCLUDE = {
    "uploadedBy": True,
    "product": True,
    "tool": True,
    # artisan->craft is nested so a media row attached only to an artisan can still fill
    # its Craft column in the hierarchy sheet.
    "artisan": {"include": {"craft": True}},
    "workshop": True,
    "craft": True,
    "questionnaireInterview": True,
}

_REPORT_KEYS = (
    "workshops",
    "crafts",
    "artisans",
    "products",
    "processes",
    "tools",
    "interviews",
    "media",
    # The design-workshop half, added 2026-08-31. Three keys and not one, because the three carry
    # different things and merging them would mean a sheet builder guessing which it had been given:
    # ``designWorkshops`` is DesignWorkshop rows, ``dwEntries`` is the flat DwStageEntry list across
    # all of them, and ``dwDefinitions`` is ``[(workshopId, CustomDefinition)]`` — populated only
    # when the path names ONE workshop, for the cost reason argued above ``_dw_report_load``.
    "designWorkshops",
    "dwEntries",
    "dwDefinitions",
)


def _tag_where(tags: list[str], ids: list[str]) -> dict[str, Any]:
    return {"AND": [{"linkedRecordType": {"in": tags}}, {"linkedRecordId": {"in": ids}}]}


async def _report_media(where: dict[str, Any] | None, scope: Scope) -> list[Any]:
    kwargs: dict[str, Any] = {
        "take": REPORT_TAKE,
        "order": {"createdAt": "desc"},
        "include": _REPORT_MEDIA_INCLUDE,
    }
    scoped = _and(where or {}, scope.media)
    if scoped:
        kwargs["where"] = scoped
    return await db.mediafile.find_many(**kwargs)


async def _report_records(segs: list[str], scope: Scope) -> dict[str, list[Any]]:
    """Load every record reachable under the given tree path, one list per report sheet.

    Root = the whole repository; a workshop path = that workshop's crafts/artisans/records; an
    artisan path = that artisan's records; record paths narrow to the single record (ancestor
    rows are kept so the sheets still interlink). users/media-types paths yield media sheets
    only, mirroring what those tree branches expose. Every query is capped at REPORT_TAKE and
    filtered by ``scope`` — the report must never contain a row the tree would not show.
    """
    data: dict[str, list[Any]] = {key: [] for key in _REPORT_KEYS}

    # THE DESIGN-WORKSHOP TAXONOMY IS ITS OWN REPORT, and it is answered before the normalisation
    # below because there is nothing to normalise it ONTO. ``Workshop`` and ``DesignWorkshop`` are
    # different tables joined only by a NULLABLE ``DesignWorkshop.workshopId`` (see the taxonomy's
    # own note in ``TAXONOMIES``), so a design workshop cannot be re-expressed as a workshop path the
    # legacy loaders understand — most of them have no such parent to be expressed under.
    if segs and segs[0] == "by-design-workshop":
        if not scope.design_workshops:
            raise HTTPException(
                status_code=status.HTTP_404_NOT_FOUND, detail="Unknown data path"
            )
        wid = segs[1] if len(segs) >= 2 else None
        await _dw_report_load(data, scope, [wid] if wid else None)
        if wid:
            data["media"] = await _report_media(_dw_media_where(wid), scope)
        elif data["designWorkshops"]:
            data["media"] = await _report_media(
                {
                    "OR": [
                        {"linkedRecordType": {"in": _DESIGN_WORKSHOP_TAGS}},
                        {"designWorkshopId": {"not": None}},
                    ]
                },
                scope,
            )
        return data

    # Normalise the taxonomy roots onto the shapes the loaders below understand.
    if segs:
        if segs[0] == "by-workshop":
            segs = ["workshops", *segs[1:]]
        elif segs[0] == "by-type":
            segs = ["media-types", *segs[1:]]
        elif segs[0] == "by-uploader":
            # by-uploader/<wid>[/<uid>[/<branch>]] — report on what that person put into
            # that workshop. Without a uid it is the whole workshop, so fall through to
            # the workshop loader below.
            if len(segs) >= 3:
                wid, uid = segs[1], segs[2]
                owned: list[dict[str, Any]] = [{"uploadedById": uid}, {"workshopId": wid}]
                if len(segs) >= 5 and segs[3] == "media" and segs[4] in _MEDIA_TYPE_WHERE:
                    owned.append(_MEDIA_TYPE_WHERE[segs[4]])
                data["media"] = await _report_media({"AND": owned}, scope)
                for branch in ("artisans", "products", "tools", "questionnaire"):
                    if len(segs) >= 4 and segs[3] not in (branch, "media"):
                        continue
                    kind, records = await _uploader_records(branch, wid, uid, scope)
                    if kind == "artisan":
                        data["artisans"] = records
                    elif kind == "product":
                        data["products"] = records
                    elif kind == "tool":
                        data["tools"] = records
                    elif kind == "interview":
                        data["interviews"] = records
                return data
            segs = ["workshops", *segs[1:]]

    if segs and segs[0] == "users":
        where: dict[str, Any] = {"uploadedById": segs[1]} if len(segs) >= 2 else {}
        if len(segs) >= 3 and segs[2] in _USER_TYPE_WHERE:
            if segs[2] == "designworkshops" and not scope.design_workshops:
                raise HTTPException(
                    status_code=status.HTTP_404_NOT_FOUND, detail="Unknown data path"
                )
            where = {"AND": [where, _user_type_where(segs[2], scope)]}
        data["media"] = await _report_media(where, scope)
        return data

    if segs and segs[0] == "media-types":
        type_where = _MEDIA_TYPE_WHERE.get(segs[1]) if len(segs) >= 2 else None
        if len(segs) >= 2 and type_where is None:
            raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Unknown data path")
        data["media"] = await _report_media(type_where, scope)
        return data

    if segs and segs[0] != "workshops":
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Unknown data path")

    if len(segs) <= 1:
        # Root (or the all-workshops folder): everything visible, each sheet capped at REPORT_TAKE.
        #
        # EIGHT INDEPENDENT SHEETS, ONE WAVE. Not one of these reads consumes another's output — the
        # root report is eight unrelated tables side by side — and ``/data/report?format=json``
        # MEASURED 19.01 round trips (13,289 ms) against production with them in series
        # (docs/SCALABILITY.md §1.2). Eight is inside ``pool_width()`` (10), so this is one wave and
        # not two; do not add a ninth read here without checking that number again, because at
        # eleven ``gather_reads`` silently splits into two waves at its semaphore.
        #
        # THE MEMORY IS UNCHANGED AND STILL CAPPED BY ``REPORT_TAKE``. All eight lists were resident
        # together before — the workbook is built from every sheet at once — so what moved is when
        # they arrive, not how much is held. §5.2 is the live ceiling here, not this wave.
        (
            data["workshops"],
            data["crafts"],
            data["artisans"],
            data["products"],
            data["processes"],
            data["tools"],
            data["interviews"],
            data["media"],
        ) = await gather_reads(
            db.workshop.find_many(
                where=scope.records,
                take=REPORT_TAKE,
                order={"createdAt": "desc"},
                include={"createdBy": True},
            ),
            db.craft.find_many(
                take=REPORT_TAKE,
                order={"createdAt": "desc"},
                include={"workshops": {"include": {"workshop": True}}},
            ),
            db.artisan.find_many(
                where=scope.records,
                take=REPORT_TAKE,
                order={"createdAt": "desc"},
                include=_ARTISAN_INCLUDE,
            ),
            db.productdocumentation.find_many(
                where=scope.records,
                take=REPORT_TAKE,
                order={"createdAt": "desc"},
                include=_PRODUCT_INCLUDE,
            ),
            db.process.find_many(
                where=scope.records,
                take=REPORT_TAKE,
                order={"createdAt": "desc"},
                include=_PROCESS_INCLUDE,
            ),
            db.tooldocumentation.find_many(
                where=scope.records,
                take=REPORT_TAKE,
                order={"createdAt": "desc"},
                include=_TOOL_INCLUDE,
            ),
            db.questionnaireinterview.find_many(
                where=scope.records,
                take=REPORT_TAKE,
                order={"createdAt": "desc"},
                include=_INTERVIEW_INCLUDE,
            ),
            _report_media(None, scope),
        )
        # THE WHOLE REPOSITORY INCLUDES THE HALF THIS SCREEN COULD NOT SEE. A root report is what
        # the "download everything" button produces, and until this line it produced the seven
        # legacy tables under a filename that promised design workshops. Loaded as its own wave for
        # the reason argued at :func:`_dw_report_load`, and a no-op for an account that may not read
        # it — so the query count for a granted researcher is exactly what it was.
        await _dw_report_load(data, scope, None)
        return data

    wid = segs[1]
    ws = await _require(
        db.workshop,
        wid,
        "Workshop",
        include={
            "createdBy": True,
            "crafts": {"include": {"craft": True}},
            "artisans": {"include": {"artisan": True}},
        },
        scope_where=scope.records,
    )
    data["workshops"] = [ws]
    linked = await _linked_artisans(ws, scope)

    # Parse the (legacy or craft-ful) path below the workshop into craft/artisan/branch parts.
    cid: str | None = None
    aid: str | None = None
    rest: list[str] = []
    if len(segs) >= 3:
        if segs[2] == "_misc":
            data["media"] = await _report_media(
                {
                    "AND": [
                        {"artisanId": None},
                        {"productId": None},
                        {"toolId": None},
                        {"questionnaireInterviewId": None},
                        _record_media_where("workshopId", wid, ["workshop"]),
                    ]
                },
                scope,
            )
            return data
        if segs[2] == "crafts":
            cid = segs[3] if len(segs) >= 4 else None
            if len(segs) >= 6 and segs[4] == "artisans":
                aid = segs[5]
                rest = list(segs[6:])
        elif segs[2] == "artisans":
            aid = segs[3] if len(segs) >= 4 else None
            rest = list(segs[4:]) if len(segs) >= 5 else []
        else:
            raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Unknown data path")

    # Artisans in scope.
    if aid is not None:
        artisan = await _require(
            db.artisan, aid, "Artisan", include=_ARTISAN_INCLUDE, scope_where=scope.records
        )
        scoped = [artisan]
    else:
        if cid == NO_CRAFT:
            ids = [a.id for a in linked if not a.craftId]
        elif cid:
            ids = [a.id for a in linked if a.craftId == cid]
        else:
            ids = [a.id for a in linked]
        scoped = (
            await db.artisan.find_many(
                where=_and({"id": {"in": ids}}, scope.records),
                take=REPORT_TAKE,
                order={"createdAt": "desc"},
                include=_ARTISAN_INCLUDE,
            )
            if ids
            else []
        )
    data["artisans"] = scoped
    aids = [a.id for a in scoped]

    # Crafts in scope.
    if aid is not None:
        craft_ids = [a.craftId for a in scoped if a.craftId]
    elif cid == NO_CRAFT:
        craft_ids = []
    elif cid:
        craft_ids = [cid]
    else:
        craft_ids = sorted(
            {link.craftId for link in ws.crafts or []} | {a.craftId for a in scoped if a.craftId}
        )
    data["crafts"] = (
        await db.craft.find_many(
            where={"id": {"in": craft_ids}},
            take=REPORT_TAKE,
            order={"createdAt": "desc"},
            include={"workshops": {"include": {"workshop": True}}},
        )
        if craft_ids
        else []
    )

    branch = rest[0] if rest else None
    if branch is not None and branch not in ("products", "tools", "questionnaire", "misc"):
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Unknown data path")
    rid = rest[1] if len(rest) >= 2 else None
    proc_id = rest[3] if len(rest) >= 4 and rest[2] == "processes" else None

    # PRODUCTS, TOOLS AND INTERVIEWS ALL KEY OFF ``aids`` AND OFF NOTHING ELSE, SO THEY GO TOGETHER.
    # The chain above this point is real — a workshop's artisans have to be known before any of the
    # three can be asked for — but these three are siblings, and awaiting them in series was three
    # cross-region round trips where one wave does. ``processes`` stays BELOW them and must: it is
    # keyed by ``pids``, which does not exist until the products have come back.
    #
    # Each closure returns exactly the list the straight-line code assigned, the empty list for a
    # branch that does not apply included — so a path that asks for one of the three still issues
    # one query and not three. At most ONE of them can take its ``_require`` arm (``branch`` holds a
    # single value and the three arms test different values of it), so there is no question of two
    # 404s racing to be the one the caller sees.

    async def _load_products() -> list[Any]:
        if branch not in (None, "products") or not aids:
            return []
        if branch == "products" and rid:
            return [
                await _require(
                    db.productdocumentation,
                    rid,
                    "Product",
                    include=_PRODUCT_INCLUDE,
                    scope_where=scope.records,
                )
            ]
        # Mirrors the tree: an artisan's products, in this workshop or workshop-less.
        return await db.productdocumentation.find_many(
            where=_and(
                {
                    "AND": [
                        {"artisanId": {"in": aids}},
                        {"OR": [{"workshopId": wid}, {"workshopId": None}]},
                    ]
                },
                scope.records,
            ),
            take=REPORT_TAKE,
            order={"createdAt": "desc"},
            include=_PRODUCT_INCLUDE,
        )

    async def _load_tools() -> list[Any]:
        if branch not in (None, "tools") or not aids:
            return []
        if branch == "tools" and rid:
            return [
                await _require(
                    db.tooldocumentation,
                    rid,
                    "Tool",
                    include=_TOOL_INCLUDE,
                    scope_where=scope.records,
                )
            ]
        return await db.tooldocumentation.find_many(
            where=_and(
                {
                    "AND": [
                        {
                            "OR": [
                                {"artisanId": {"in": aids}},
                                {"artisanLinks": {"some": {"artisanId": {"in": aids}}}},
                            ]
                        },
                        {"OR": [{"workshopId": wid}, {"workshopId": None}]},
                    ]
                },
                scope.records,
            ),
            take=REPORT_TAKE,
            order={"createdAt": "desc"},
            include=_TOOL_INCLUDE,
        )

    async def _load_interviews() -> list[Any]:
        if branch not in (None, "questionnaire") or not aids:
            return []
        if branch == "questionnaire" and rid:
            return [
                await _require(
                    db.questionnaireinterview,
                    rid,
                    "Interview",
                    include=_INTERVIEW_INCLUDE,
                    scope_where=scope.records,
                )
            ]
        return await db.questionnaireinterview.find_many(
            where=_and({"artisans": {"some": {"artisanId": {"in": aids}}}}, scope.records),
            take=REPORT_TAKE,
            order={"createdAt": "desc"},
            include=_INTERVIEW_INCLUDE,
        )

    products, tools, interviews = await gather_reads(
        _load_products(), _load_tools(), _load_interviews()
    )
    data["products"] = products
    data["tools"] = tools
    data["interviews"] = interviews
    pids = [p.id for p in products]

    # A SECOND WAVE, AND A GENUINE DEPENDENCY: ``pids`` comes out of the products above. This is the
    # one read in this block that cannot join the wave, and it is why the block is two waits and not
    # one.
    processes: list[Any] = []
    if pids:
        if proc_id:
            proc = await db.process.find_first(
                where=_and({"id": proc_id}, scope.records), include=_PROCESS_INCLUDE
            )
            processes = [proc] if proc is not None else []
        else:
            processes = await db.process.find_many(
                where=_and({"productId": {"in": pids}}, scope.records),
                take=REPORT_TAKE,
                order={"createdAt": "desc"},
                include=_PROCESS_INCLUDE,
            )
    data["processes"] = processes

    if branch == "misc" and aid is not None:
        # Mirrors the tree's artisan misc listing exactly.
        data["media"] = await _report_media(
            {
                "OR": [
                    {"AND": [{"linkedRecordType": "artisan"}, {"linkedRecordId": aid}]},
                    {"AND": [{"artisanId": aid}, {"linkedRecordType": None}]},
                ]
            },
            scope,
        )
        return data

    ors: list[dict[str, Any]] = []
    if cid is None and aid is None and branch is None:
        # Whole-workshop scope also covers workshop-level (misc) media.
        ors.append(_record_media_where("workshopId", wid, ["workshop"]))
    if aids and branch is None:
        ors.append({"artisanId": {"in": aids}})
        ors.append(_tag_where(["artisan"], aids))
    if pids:
        ors.append({"productId": {"in": pids}})
        ors.append(_tag_where(["product"], pids))
    if processes:
        proc_ids = [p.id for p in processes]
        step_ids = [s.id for p in processes for s in (p.steps or [])]
        ors.append(_tag_where(["process"], proc_ids))
        if step_ids:
            ors.append(_tag_where(["processstep"], step_ids))
    if tools:
        tool_ids = [t.id for t in tools]
        ors.append({"toolId": {"in": tool_ids}})
        ors.append(_tag_where(["tool"], tool_ids))
    if interviews:
        interview_ids = [i.id for i in interviews]
        ors.append({"questionnaireInterviewId": {"in": interview_ids}})
        ors.append(_tag_where(["questionnaire", "questionnaireinterview"], interview_ids))
    data["media"] = await _report_media({"OR": ors}, scope) if ors else []
    return data


def _sheet(
    name: str,
    color: str,
    columns: list[str],
    rows: list[list[Any]],
    truncated: bool = False,
    prose: list[int] | None = None,
) -> dict[str, Any]:
    """One sheet payload.

    An over-long sheet is clipped and flagged twice: as a trailing note row (so the
    downloaded .xlsx carries the warning inline) and as a ``truncated`` flag (so the web
    viewer can render a banner instead of showing the note as a fake data row).

    ``prose`` names the column indexes whose cells hold stored Markdown rather than a label; see
    ``_rendered`` for what the .xlsx does with them.
    """
    capped = truncated or len(rows) > REPORT_TAKE
    if capped:
        rows = rows[:REPORT_TAKE]
        note = f"Note: capped at {REPORT_TAKE} rows — the full data set has more."
        rows = [*rows, [note] + [""] * (len(columns) - 1)]
    return {
        "name": name,
        "color": color,
        "columns": columns,
        "rows": rows,
        "truncated": capped,
        "prose": prose or [],
    }


# ---------------------------------------------------------------------------
# Media indexing: which record does each file belong to, and where does that
# record sit in the workshop -> craft -> artisan hierarchy.
# ---------------------------------------------------------------------------

# Most specific relation first — a photo of a product is filed under the product, not
# under the artisan who also happens to be on the row.
_OWNER_TAGS = ("processstep", "process")
_OWNER_FKS = (
    "productId",
    "toolId",
    "questionnaireInterviewId",
    "artisanId",
    "workshopId",
    "craftId",
)


def _media_owner_id(m: Any) -> str | None:
    """The single record id a media file is filed under, most specific relation first."""
    tag = (getattr(m, "linkedRecordType", None) or "").strip().lower()
    if tag in _OWNER_TAGS and getattr(m, "linkedRecordId", None):
        return m.linkedRecordId
    for fk in _OWNER_FKS:
        value = getattr(m, fk, None)
        if value:
            return value
    # Tag-only attachment (the FK column was never populated).
    return getattr(m, "linkedRecordId", None)


def _index_media_by_record(media: list[Any]) -> dict[str, list[Any]]:
    """record id -> its media files, each file counted exactly once."""
    index: dict[str, list[Any]] = {}
    for m in media:
        owner = _media_owner_id(m)
        if owner:
            index.setdefault(owner, []).append(m)
    return index


def _hierarchy_of(kind: str, record: Any) -> tuple[str, str, str]:
    """(workshop, craft, artisan) display names for a record, for the flat sheets."""
    if kind == "workshop":
        return _cell(record.title), "", ""
    if kind == "craft":
        titles = [
            getattr(getattr(link, "workshop", None), "title", None)
            for link in getattr(record, "workshops", None) or []
        ]
        return _cell(", ".join(t for t in titles if t)), _cell(record.name), ""
    if kind == "artisan":
        titles = [
            getattr(getattr(link, "workshop", None), "title", None)
            for link in getattr(record, "workshops", None) or []
        ]
        return (
            _cell(", ".join(t for t in titles if t)),
            _cell(getattr(getattr(record, "craft", None), "name", None)),
            _cell(record.name),
        )
    if kind in ("product", "tool"):
        return (
            _cell(getattr(getattr(record, "workshop", None), "title", None)),
            _cell(record.craftName),
            _cell(record.artisanName),
        )
    if kind == "process":
        product = getattr(record, "product", None)
        return (
            _cell(getattr(getattr(product, "workshop", None), "title", None)),
            _cell(getattr(product, "craftName", None)),
            _cell(getattr(product, "artisanName", None)),
        )
    if kind == "interview":
        return "", "", _cell(", ".join(artisan_names(record)))
    return "", "", ""


def _media_link_label(
    m: Any,
    proc_names: dict[str, str],
    step_names: dict[str, str],
    dw_attributions: dict[str, dw.MediaAttribution] | None = None,
) -> str:
    """Human 'record link' for one media row, most specific relation first.

    THE DESIGN-WORKSHOP BRANCH IS FIRST AND IT IS A BUG FIX. Every photograph and recording taken
    inside a design & prototype workshop reached the bottom of this function and came back
    "Miscellaneous" — no relation on the row matches, because a design workshop is reached by the
    ``designWorkshop`` tag pair and by ``designWorkshopId``, neither of which is one of the six
    relations tested below. So the four media sheets in the workbook filed the entire stage record
    of the product this repository is named after under the label for files that belong to nothing.

    ``dw_attributions`` is the index built by ``dw.media_attributions`` from the workshop's own
    stage rows — the ONLY place the stage is recorded (no column on ``MediaFile`` carries it). It is
    optional so that a caller with no design workshops loaded pays nothing and reads exactly as
    before; when it is absent, or the file is a loose upload no stage row cites, the workshop is
    still named from the row itself rather than falling through to "Miscellaneous".
    """
    tag = (m.linkedRecordType or "").strip().lower()
    if tag in {t.lower() for t in _DESIGN_WORKSHOP_TAGS} or getattr(m, "designWorkshopId", None):
        attribution = (dw_attributions or {}).get(m.id)
        if attribution is not None:
            title = attribution.workshop_title or "Design workshop"
            return f"Design workshop: {title} / {attribution.label}"
        return "Design workshop"
    if tag == "process":
        name = proc_names.get(m.linkedRecordId or "")
        return f"Process: {name}" if name else "Process"
    if tag == "processstep":
        name = step_names.get(m.linkedRecordId or "")
        return f"Process step: {name}" if name else "Process step"
    product = getattr(m, "product", None)
    if product is not None:
        return f"Product: {_cell(product.productName) or 'Product'}"
    tool = getattr(m, "tool", None)
    if tool is not None:
        return f"Tool: {_cell(tool.toolkitName) or 'Tool'}"
    interview = getattr(m, "questionnaireInterview", None)
    if interview is not None:
        return f"Interview: {_cell(interview.title) or 'Interview'}"
    artisan = getattr(m, "artisan", None)
    if artisan is not None:
        return f"Artisan: {_cell(artisan.name) or 'Artisan'}"
    workshop = getattr(m, "workshop", None)
    if workshop is not None:
        return f"Workshop: {_cell(workshop.title) or 'Workshop'}"
    craft = getattr(m, "craft", None)
    if craft is not None:
        return f"Craft: {_cell(craft.name) or 'Craft'}"
    return "Miscellaneous"


def _media_context(
    m: Any,
    proc_names: dict[str, str],
    step_names: dict[str, str],
    dw_attributions: dict[str, dw.MediaAttribution] | None = None,
) -> dict[str, str]:
    """Where one media file sits: its workshop/craft/artisan plus what it is attached to.

    Resolved from whichever relations the row actually carries — a product photo gets its
    craft and artisan from the product's denormalised names, a loose workshop upload gets
    only the workshop.
    """
    product = getattr(m, "product", None)
    tool = getattr(m, "tool", None)
    artisan = getattr(m, "artisan", None)
    workshop = getattr(m, "workshop", None)
    craft = getattr(m, "craft", None)

    workshop_name = _cell(getattr(workshop, "title", None))
    craft_name = _cell(getattr(craft, "name", None))
    artisan_name = _cell(getattr(artisan, "name", None))

    if product is not None:
        craft_name = craft_name or _cell(product.craftName)
        artisan_name = artisan_name or _cell(product.artisanName)
    if tool is not None:
        craft_name = craft_name or _cell(tool.craftName)
        artisan_name = artisan_name or _cell(tool.artisanName)
    if artisan is not None and not craft_name:
        craft_name = _cell(getattr(getattr(artisan, "craft", None), "name", None))

    label = _media_link_label(m, proc_names, step_names, dw_attributions)
    attached_kind, _, attached_name = label.partition(": ")
    # A DESIGN-WORKSHOP FILE FILLS THE "Workshop" COLUMN FROM ITS OWN WORKSHOP. That column is read
    # off the ``workshop`` relation, which points at the LEGACY Workshop table and is null on every
    # one of these rows — so without this the workbook printed a blank workshop beside a file whose
    # workshop is the whole reason it exists, and the sheets sorted by that column put them all
    # together under the empty string.
    attribution = (dw_attributions or {}).get(m.id)
    if attribution is not None and not workshop_name:
        workshop_name = attribution.workshop_title
    return {
        "workshop": workshop_name,
        "craft": craft_name,
        "artisan": artisan_name,
        "attachedTo": attached_kind,
        "record": attached_name or "",
        "label": label,
    }


def _media_facts(m: Any) -> dict[str, str]:
    """The per-file cells shared by all three media taxonomy sheets."""
    return {
        "file": _cell(m.originalFilename) or _cell(m.id),
        "type": _cell(str(_ev(m.mediaType)).title()),
        "size": _human_size(m.sizeBytes),
        "uploadedBy": _cell(getattr(getattr(m, "uploadedBy", None), "name", None)),
        "uploadedOn": _cell(_date(m.createdAt)),
        "recordedOn": _cell(_date(m.recordedAt or m.createdAt)),
        "transcript": "Yes" if (m.transcriptText or "").strip() else "",
        "url": _cell(m.url),
    }


# ---------------------------------------------------------------------------
# Sheet builders
# ---------------------------------------------------------------------------


def _record_sheet(
    kind: str, records: list[Any], media_index: dict[str, list[Any]]
) -> dict[str, Any]:
    """One sheet per record type, columns straight from the shared field registry so the
    sheet, the browser's info card and the in-folder table can never disagree. Each row
    carries its own media inline (count / filenames / URLs)."""
    spec = SPECS[kind]
    rows = [sheet_row(kind, r, media_index.get(r.id, [])) for r in records]
    return _sheet(
        spec.plural, spec.color, sheet_columns(kind), rows, truncated=len(records) >= REPORT_TAKE
    )


def _process_step_sheet(processes: list[Any], media_index: dict[str, list[Any]]) -> dict[str, Any]:
    """Steps get their own sheet rather than being interleaved as half-empty rows in the
    Processes sheet, so both stay rectangular and sortable."""
    columns = [
        "Step",
        "Process",
        "Product",
        "Artisan",
        "Step #",
        "Step type",
        "Notes",
        *MEDIA_COLUMNS,
    ]
    rows: list[list[Any]] = []
    for pr in processes:
        product = getattr(pr, "product", None)
        for step in sorted(getattr(pr, "steps", None) or [], key=lambda s: s.sortOrder):
            rows.append(
                [
                    _cell(step.name),
                    _cell(pr.name),
                    _cell(getattr(product, "productName", None)),
                    _cell(getattr(product, "artisanName", None)),
                    step.sortOrder,
                    _cell(_enum_label(step.stepType)),
                    _cell(step.notes),
                    *media_row(media_index.get(step.id, [])),
                ]
            )
    return _sheet("Process steps", SPECS["process"].color, columns, rows)


def _questionnaire_answer_sheet(interviews: list[Any]) -> dict[str, Any]:
    """The per-question answers, one row each, beside the interview-level sheet."""
    columns = ["Interview", "Artisans", "Section", "Question", "Answer", "Notes"]
    rows: list[list[Any]] = []
    for interview in interviews:
        label = _cell(_interview_label(interview))
        names = _cell(", ".join(artisan_names(interview)))
        responses = sorted(
            interview.responses or [],
            key=lambda r: getattr(getattr(r, "question", None), "sortOrder", 0) or 0,
        )
        for r in responses:
            q = getattr(r, "question", None)
            section = (
                (getattr(q, "sectionTitle", None) or getattr(q, "sectionCode", None)) if q else None
            )
            rows.append(
                [
                    label,
                    names,
                    _cell(section),
                    _cell((getattr(q, "prompt", None) if q else None) or r.questionId),
                    _cell(r.answerText),
                    _cell(r.notes),
                ]
            )
    return _sheet("Questionnaire answers", SPECS["interview"].color, columns, rows)


def _all_records_sheet(
    data: dict[str, list[Any]], media_index: dict[str, list[Any]]
) -> dict[str, Any]:
    """THE coalesced sheet: every record of every type on one page, each row carrying its
    full workshop -> craft -> artisan placement, its media, and its complete field dump.

    This is the single page to hand someone who wants the whole taxonomy at once without
    hopping between eight tabs.
    """
    columns = [
        "Workshop",
        "Craft",
        "Artisan",
        "Record type",
        "Record",
        *PROVENANCE_COLUMNS,
        *MEDIA_COLUMNS,
        "All fields",
    ]
    order = (
        ("workshop", "workshops"),
        ("craft", "crafts"),
        ("artisan", "artisans"),
        ("product", "products"),
        ("process", "processes"),
        ("tool", "tools"),
        ("interview", "interviews"),
    )
    rows: list[list[Any]] = []
    for kind, key in order:
        spec = SPECS[kind]
        for record in data.get(key) or []:
            workshop, craft, artisan = _hierarchy_of(kind, record)
            rows.append(
                [
                    workshop,
                    craft,
                    artisan,
                    spec.label,
                    _cell(spec.title(record)),
                    *provenance_row(record),
                    *media_row(media_index.get(record.id, [])),
                    _info_text(info_panel(kind, record)),
                ]
            )
    return _sheet("All records", OVERVIEW_COLOR, columns, rows)


def _transcript_sheet(
    media: list[Any],
    proc_names: dict[str, str],
    step_names: dict[str, str],
    dw_attributions: dict[str, dw.MediaAttribution] | None = None,
) -> dict[str, Any]:
    columns = [
        "File",
        "Type",
        "Workshop",
        "Craft",
        "Artisan",
        "Linked record",
        "Uploaded by",
        "Recorded on",
        "Transcript",
    ]
    rows = []
    for m in media:
        if not (m.transcriptText or "").strip():
            continue
        ctx = _media_context(m, proc_names, step_names, dw_attributions)
        facts = _media_facts(m)
        rows.append(
            [
                facts["file"],
                facts["type"],
                ctx["workshop"],
                ctx["craft"],
                ctx["artisan"],
                ctx["label"],
                facts["uploadedBy"],
                facts["recordedOn"],
                _cell(m.transcriptText),
            ]
        )
    # ``len(media) >= REPORT_TAKE``, NOT the row count, exactly as the three media sheets below do.
    # These rows are the subset of ``media`` carrying a transcript, and ``media`` was already capped
    # at REPORT_TAKE by ``_report_media``; leaving this argument off fell back to ``_sheet``'s own
    # ``len(rows) > REPORT_TAKE``, which a short subset can never satisfy. With 5,000 media capped
    # and a few hundred of them transcribed, the sheet was cut upstream and flagged ``truncated:
    # false`` — a silent cut, which is the one thing a cap here may not be.
    return _sheet(
        "Transcripts",
        TRANSCRIPT_COLOR,
        columns,
        rows,
        len(media) >= REPORT_TAKE,
        prose=[columns.index("Transcript")],
    )


def _media_by_hierarchy_sheet(
    media: list[Any],
    proc_names: dict[str, str],
    step_names: dict[str, str],
    dw_attributions: dict[str, dw.MediaAttribution] | None = None,
) -> dict[str, Any]:
    """Media taxonomy 1 — the default browse order: workshop -> craft -> artisan -> record."""
    columns = [
        "Workshop",
        "Craft",
        "Artisan",
        "Attached to",
        "Record",
        "File",
        "Type",
        "Size",
        "Transcript",
        "Uploaded by",
        "Uploaded on",
        "URL",
    ]
    rows = []
    for m in media:
        ctx = _media_context(m, proc_names, step_names, dw_attributions)
        f = _media_facts(m)
        rows.append(
            [
                ctx["workshop"],
                ctx["craft"],
                ctx["artisan"],
                ctx["attachedTo"],
                ctx["record"],
                f["file"],
                f["type"],
                f["size"],
                f["transcript"],
                f["uploadedBy"],
                f["uploadedOn"],
                f["url"],
            ]
        )
    rows.sort(key=lambda r: (r[0].lower(), r[1].lower(), r[2].lower(), r[5].lower()))
    return _sheet("Media by hierarchy", MEDIA_COLOR, columns, rows, len(media) >= REPORT_TAKE)


def _media_by_uploader_sheet(
    media: list[Any],
    proc_names: dict[str, str],
    step_names: dict[str, str],
    dw_attributions: dict[str, dw.MediaAttribution] | None = None,
) -> dict[str, Any]:
    """Media taxonomy 2 — who uploaded what, and into which workshop."""
    columns = [
        "Uploaded by",
        "Workshop",
        "Attached to",
        "Record",
        "File",
        "Type",
        "Size",
        "Transcript",
        "Uploaded on",
        "URL",
    ]
    rows = []
    for m in media:
        ctx = _media_context(m, proc_names, step_names, dw_attributions)
        f = _media_facts(m)
        rows.append(
            [
                f["uploadedBy"],
                ctx["workshop"],
                ctx["attachedTo"],
                ctx["record"],
                f["file"],
                f["type"],
                f["size"],
                f["transcript"],
                f["uploadedOn"],
                f["url"],
            ]
        )
    rows.sort(key=lambda r: (r[0].lower(), r[1].lower(), r[4].lower()))
    return _sheet("Media by uploader", MEDIA_COLOR, columns, rows, len(media) >= REPORT_TAKE)


def _media_by_type_sheet(
    media: list[Any],
    proc_names: dict[str, str],
    step_names: dict[str, str],
    dw_attributions: dict[str, dw.MediaAttribution] | None = None,
) -> dict[str, Any]:
    """Media taxonomy 3 — grouped by kind of file (audios, videos, images, documents)."""
    columns = [
        "Type",
        "File",
        "Workshop",
        "Artisan",
        "Attached to",
        "Record",
        "Size",
        "Transcript",
        "Uploaded by",
        "Uploaded on",
        "URL",
    ]
    rows = []
    for m in media:
        ctx = _media_context(m, proc_names, step_names, dw_attributions)
        f = _media_facts(m)
        rows.append(
            [
                f["type"],
                f["file"],
                ctx["workshop"],
                ctx["artisan"],
                ctx["attachedTo"],
                ctx["record"],
                f["size"],
                f["transcript"],
                f["uploadedBy"],
                f["uploadedOn"],
                f["url"],
            ]
        )
    rows.sort(key=lambda r: (r[0].lower(), r[1].lower()))
    return _sheet("Media by type", MEDIA_COLOR, columns, rows, len(media) >= REPORT_TAKE)


# ---------------------------------------------------------------------------
# The design-workshop half of /data/report.
#
# WHAT THE WORKBOOK GAINS: an overview page (one row per design workshop), an INDEX page naming all
# 44 registry entities with the rows found for each, and one sheet per entity that has rows, capped
# at ``dw.MAX_ENTITY_SHEETS``. The grouping argument — why the entity and not the stage is the
# sheet, and why the index page exists at all — is written out at ``dw.sheet_plan``.
#
# THE DESIGNER'S OWN QUESTIONS ARE A SHEET ONLY WHEN THE PATH NAMES ONE WORKSHOP, and that is a
# COST decision stated rather than hidden. Their columns are defined per workshop in
# DwCustomSection/DwCustomField, so naming the columns of four hundred workshops means four hundred
# definition loads on one request — and the columns would not be shared anyway: two designers write
# "Dye bath?" and mean different questions, so the merged sheet would be a thousand columns wide
# with one cell filled per row. At the root the rows are COUNTED on the index page instead, with the
# sentence that says where to read them. Rule 10 is satisfied by the count, not by the sheet.
# ---------------------------------------------------------------------------

#: The tab colour of every design-workshop sheet, so they read as one block in a 30-tab workbook.
#: Defined here rather than in ``services/record_fields.py`` because that module's palette is keyed
#: by the seven legacy record types and a design workshop is not one of them.
DW_COLOR = "#0F766E"

#: The ``group`` stamped on every design-workshop sheet.
#:
#: A KEY AND NOT THE COLOUR, and the difference is a permission rule rather than a style one. The
#: xlsx branch of ``/data/report`` drops these sheets for a caller who may read them and not export
#: them, and selecting them by ``sheet["color"] == DW_COLOR`` would mean any future sheet that
#: happened to be teal left the building with them — a palette edit silently changing who may
#: download what. The web viewer also reads it, to badge the block it may not download.
DW_SHEET_GROUP = "designWorkshop"


def _dw_stamp(sheet: dict[str, Any]) -> dict[str, Any]:
    """Mark one sheet as design-workshop data. See :data:`DW_SHEET_GROUP`."""
    return {**sheet, "group": DW_SHEET_GROUP}


async def _dw_report_load(
    data: dict[str, list[Any]], scope: Scope, workshop_ids: list[str] | None
) -> None:
    """Load design workshops, their live stage rows and (for a single workshop) its definition.

    ``workshop_ids`` of ``None`` means every workshop in the repository — the root report. A LIST
    narrows to those ids; an EMPTY list would mean "no workshops", which no caller here has any way
    to ask for, so it is treated as the narrow case and correctly returns nothing.

    NOTHING IS LOADED AT ALL FOR AN ACCOUNT THAT MAY NOT READ THIS DATA, so the professor/researcher
    split costs a granted researcher exactly zero extra queries and their workbook is byte-for-byte
    the one they got before this change.

    A SECOND WAVE, DELIBERATELY, AND NOT FOLDED INTO THE ROOT REPORT'S EIGHT-READ GATHER. That
    gather's own comment records the measurement behind it and warns that eleven coroutines split
    silently into two waves at ``gather_reads``' semaphore (``pool_width()``, ten). Two more reads
    there would be ten — right on the edge, and the next person to add a sheet would tip it over
    without knowing they had. The entries read genuinely depends on the workshop ids anyway, so it
    could never have been in the same wave.
    """
    if not scope.design_workshops:
        return
    where: dict[str, Any] = {"deletedAt": None}
    if workshop_ids is not None:
        where["id"] = {"in": workshop_ids}
    records = await db.designworkshop.find_many(
        where=where, take=REPORT_TAKE, order={"createdAt": "desc"}
    )
    data["designWorkshops"] = records
    if not records:
        return
    ids = [record.id for record in records]
    data["dwEntries"] = await db.dwstageentry.find_many(
        where={"designWorkshopId": {"in": ids}, "deletedAt": None},
        take=REPORT_TAKE,
        order=[{"designWorkshopId": "asc"}, {"ordinal": "asc"}],
    )
    if len(records) == 1:
        data["dwDefinitions"] = [(records[0].id, await load_definition_or_empty(records[0].id))]


def _dw_by_workshop(data: dict[str, list[Any]]) -> dict[str, list[Any]]:
    """``{workshopId: its stage rows}`` from the flat entry list the loader returns."""
    grouped: dict[str, list[Any]] = {}
    for entry in data["dwEntries"]:
        grouped.setdefault(str(getattr(entry, "designWorkshopId", "") or ""), []).append(entry)
    return grouped


def _dw_attributions(data: dict[str, list[Any]]) -> dict[str, dw.MediaAttribution]:
    """Every loaded workshop's media attributions, merged into one index.

    Merged across workshops without a collision check because a ``MediaFile`` id is a cuid and a
    stage row cites it by that id; the same file appearing in two DIFFERENT workshops' stage rows
    would be a data defect, not a shape this has to arbitrate. Within one workshop the first
    citation wins — see ``dw.media_attributions``.
    """
    grouped = _dw_by_workshop(data)
    index: dict[str, dw.MediaAttribution] = {}
    for record in data["designWorkshops"]:
        index.update(dw.media_attributions(record, grouped.get(record.id, [])))
    return index


def _dw_overview_sheet(data: dict[str, list[Any]]) -> dict[str, Any]:
    """One row per design workshop: the promoted columns, plus how much of it has been filled in.

    THE TWO COVERAGE COLUMNS ARE WHY THIS SHEET EXISTS RATHER THAN A LINK TO THE WORKSHOPS LIST. A
    research question about a corpus of design workshops almost always starts "how many got as far
    as X" — and a per-workshop sheet with 22 stage columns would be unreadable, while a bare list of
    titles answers nothing. Stages answered and rows recorded are the two numbers that let a
    researcher decide which workshops are worth opening.
    """
    grouped = _dw_by_workshop(data)
    columns = [column.label for column in dw.WORKSHOP_IDENTITY_COLUMNS] + [
        "Stages answered",
        "Rows recorded",
    ]
    rows = []
    for record in data["designWorkshops"]:
        identity = dw.workshop_identity(record)
        entries = grouped.get(record.id, [])
        answered = {
            found[0].key
            for found in (
                dw.entity_by_key(str(getattr(entry, "entityKey", "") or "")) for entry in entries
            )
            if found is not None
        }
        rows.append(
            [identity.get(column.key, "") for column in dw.WORKSHOP_IDENTITY_COLUMNS]
            + [f"{len(answered)} of {len(dw.stages())}", str(len(entries))]
        )
    return _sheet(
        "Design workshops",
        DW_COLOR,
        columns,
        rows,
        truncated=len(data["designWorkshops"]) >= REPORT_TAKE,
    )


def _dw_index_sheet(
    data: dict[str, list[Any]], counts: dict[str, int], plan: dw.SheetPlan, unknown: dict[str, int]
) -> dict[str, Any]:
    """THE ANTI-SILENCE PAGE. Every one of the 44 registry entities, whether or not it got a sheet.

    Rule 10 applied to a workbook: a list that quietly stops is indistinguishable from a place with
    no records. Without this page a reader who found no "Market survey response" tab would conclude
    the workshops did no market survey, when the truth might be that the tab budget ran out three
    entities earlier. So every entity is named with its stage, its row count, and one of three
    verdicts — its own sheet, counted-but-not-shown, or genuinely zero.

    The two rows that are not registry entities are here for the same reason: the designer's own
    questions (outside the registry by design) and any entity key written against a NEWER registry
    than this server runs. Both would otherwise be rows nothing in the workbook accounts for.
    """
    columns = ["Stage", "Stage name", "Table", "Rows", "In this workbook", "Table key"]
    rows: list[list[Any]] = []
    for table in dw.tables():
        count = counts.get(table.entity_key, 0)
        if table.entity_key in plan.included:
            verdict = "Its own sheet"
        elif table.entity_key in plan.omitted:
            verdict = f"Not shown — only {dw.MAX_ENTITY_SHEETS} tables fit one workbook"
        else:
            verdict = "No rows found"
        rows.append(
            [
                table.stage_number,
                table.stage_title,
                table.title,
                count,
                verdict,
                table.entity_key,
            ]
        )

    custom_rows = counts.get(dw.CUSTOM_ENTITY_KEY, 0)
    if custom_rows:
        rows.append(
            [
                "",
                "Outside the 22 stages",
                "The designer's own questions",
                custom_rows,
                "Its own sheet"
                if data["dwDefinitions"]
                else "Not shown — open one design workshop to read these",
                dw.CUSTOM_ENTITY_KEY,
            ]
        )
    for entity_key, count in sorted(unknown.items()):
        rows.append(
            [
                "",
                "Unknown to this server",
                entity_key,
                count,
                "Not shown — written against a newer version of the form",
                entity_key,
            ]
        )
    # NOT ``_sheet(..., truncated=...)``, AND THE DIFFERENCE IS A WRONG SENTENCE ON SCREEN.
    # ``_sheet`` treats its flag as "the per-sheet ROW cap bit": it clips the rows and appends
    # "Note: capped at 5000 rows — the full data set has more." This page is 44 rows long and was
    # never row-capped; what ran out is the TAB budget, which is a different fact with a different
    # next move. So the flag is set afterwards and carries its own sentence, and the web viewer
    # prefers that sentence over its default banner.
    sheet = _sheet("DW tables", DW_COLOR, columns, rows)
    if not plan.truncated:
        return sheet
    return {
        **sheet,
        "truncated": True,
        "truncatedNote": (
            f"{len(plan.omitted)} more table"
            f"{'s' if len(plan.omitted) != 1 else ''} have rows and did not fit this workbook. "
            "They are listed above with their row counts — open one design workshop to read them."
        ),
    }


def _dw_entity_sheet(entity_key: str, rows: list[dict[str, str]]) -> dict[str, Any] | None:
    """One entity's rows as a sheet: workshop identity, the row's own ids, then its fields."""
    found = dw.entity_by_key(entity_key)
    if found is None:
        return None
    stage, entity = found
    headers = [column.label for column in dw.WORKSHOP_IDENTITY_COLUMNS]
    headers += ["Row id", "Row order"]
    headers += [
        f"{column.label} ({column.unit})" if column.unit else column.label
        for column in dw.entity_columns(entity)
    ]
    body = []
    for row in rows:
        cells = [row.get(column.key, "") for column in dw.WORKSHOP_IDENTITY_COLUMNS]
        cells += [row.get("entry.id", ""), row.get("entry.ordinal", "")]
        cells += [row.get(column.key, "") for column in dw.entity_columns(entity)]
        body.append(cells)
    return _sheet(
        f"{stage.number:02d} {entity.title}",
        DW_COLOR,
        headers,
        body,
        truncated=len(rows) >= REPORT_TAKE,
    )


def _dw_custom_sheet(
    definition: Any, rows: list[dict[str, str]]
) -> dict[str, Any] | None:
    """The designer's own questions, for the one-workshop case. See the block comment above."""
    if not rows:
        return None
    columns = dw.custom_columns(definition)
    headers = [column.label for column in dw.WORKSHOP_IDENTITY_COLUMNS] + ["Row id", "Row order"]
    headers += [column.label for column in columns]
    body = []
    for row in rows:
        cells = [row.get(column.key, "") for column in dw.WORKSHOP_IDENTITY_COLUMNS]
        cells += [row.get("entry.id", ""), row.get("entry.ordinal", "")]
        cells += [row.get(column.key, "") for column in columns]
        body.append(cells)
    return _sheet("Designer's own questions", DW_COLOR, headers, body)


def _dw_withheld_sheet(dropped: list[dict[str, Any]]) -> dict[str, Any]:
    """The one sheet a non-exporter's workbook carries in place of the design-workshop block.

    It names each withheld sheet and its row count, so the file states what it is missing rather than
    looking like a repository with no design workshops in it. The counts are not the data — a row
    total is a fact about coverage, which is what the reader needs in order to know whether to ask.
    """
    rows = [[sheet["name"], len(sheet["rows"])] for sheet in dropped]
    return {
        **_sheet(
            "Design workshops (withheld)",
            DW_COLOR,
            ["Sheet not included", "Rows it holds"],
            rows,
        ),
        "truncated": True,
        "truncatedNote": (
            "Design & prototype workshop tables are readable on screen at your access level and are "
            "not included in downloads. An admin or the master admin can export them."
        ),
    }


def _dw_sheets(data: dict[str, list[Any]]) -> list[dict[str, Any]]:
    """Every design-workshop sheet, in tab order, or nothing at all when there is nothing to say.

    NOTHING AT ALL RATHER THAN EMPTY SHEETS. A subtree with no design workshops in it — an artisan's
    products, say — gains no tabs, so the workbook a researcher already knows is unchanged wherever
    this data does not reach. The index page appears only alongside the data it indexes.
    """
    records = data["designWorkshops"]
    if not records:
        return []

    grouped_entries = _dw_by_workshop(data)
    definitions = dict(data["dwDefinitions"])

    # One flattened bundle per workshop, merged by entity. ``flatten`` is the shared implementation
    # (see services/design_workshop_data.py); this loop only concatenates what it returns, so the
    # sheets and the tree cannot disagree about what a stored value renders as.
    merged: dict[str, list[dict[str, str]]] = {}
    unknown: dict[str, int] = {}
    for record in records:
        rows, unknowns = dw.flatten(
            record, grouped_entries.get(record.id, []), definitions.get(record.id)
        )
        for entity_key, entity_rows in rows.items():
            merged.setdefault(entity_key, []).extend(entity_rows)
        for item in unknowns:
            unknown[item.entity_key] = unknown.get(item.entity_key, 0) + item.rows

    counts = {key: len(value) for key, value in merged.items()}
    # The ``_custom`` rows a definition could not name come back as UNKNOWN, not as a count of zero
    # — that is what ``flatten`` reports when it has no definition. Counting them here as custom
    # rows keeps the index page's arithmetic honest at the root, where no definition is loaded.
    counts[dw.CUSTOM_ENTITY_KEY] = counts.get(dw.CUSTOM_ENTITY_KEY, 0) + unknown.pop(
        dw.CUSTOM_ENTITY_KEY, 0
    )
    plan = dw.sheet_plan(counts)

    sheets = [_dw_overview_sheet(data), _dw_index_sheet(data, counts, plan, unknown)]
    for entity_key in plan.included:
        sheet = _dw_entity_sheet(entity_key, merged.get(entity_key, []))
        if sheet is not None:
            sheets.append(sheet)
    for _workshop_id, definition in data["dwDefinitions"]:
        custom = _dw_custom_sheet(definition, merged.get(dw.CUSTOM_ENTITY_KEY, []))
        if custom is not None:
            sheets.append(custom)
    # Stamped in ONE place, on the way out, so a sheet added to this function cannot be the one that
    # forgets — which for this stamp means leaving the building with an account that may not take it.
    return [_dw_stamp(sheet) for sheet in sheets]

def _report_sheets(data: dict[str, list[Any]]) -> list[dict[str, Any]]:
    """Every sheet in the workbook, in tab order.

    Layout: the coalesced All-records page first (the whole taxonomy at a glance), then
    one page per record type, then the questionnaire answers and process steps, then the
    transcripts, and finally the same media set presented under each of the three
    taxonomies the browser offers.
    """
    media = data["media"]
    proc_names = {p.id: _cell(p.name) for p in data["processes"]}
    step_names = {s.id: _cell(s.name) for p in data["processes"] for s in (p.steps or [])}
    media_index = _index_media_by_record(media)
    # WHAT EACH DESIGN-WORKSHOP FILE IS EVIDENCE OF, resolved once and handed to all four media
    # sheets. Without it every one of them printed "Miscellaneous" in the "Attached to" column for a
    # stage photograph — see :func:`_media_link_label`.
    attributions = _dw_attributions(data)

    return [
        _all_records_sheet(data, media_index),
        _record_sheet("workshop", data["workshops"], media_index),
        _record_sheet("craft", data["crafts"], media_index),
        _record_sheet("artisan", data["artisans"], media_index),
        _record_sheet("product", data["products"], media_index),
        _record_sheet("process", data["processes"], media_index),
        _process_step_sheet(data["processes"], media_index),
        _record_sheet("tool", data["tools"], media_index),
        _record_sheet("interview", data["interviews"], media_index),
        _questionnaire_answer_sheet(data["interviews"]),
        *_dw_sheets(data),
        _transcript_sheet(media, proc_names, step_names, attributions),
        _media_by_hierarchy_sheet(media, proc_names, step_names, attributions),
        _media_by_uploader_sheet(media, proc_names, step_names, attributions),
        _media_by_type_sheet(media, proc_names, step_names, attributions),
    ]


def _rendered(sheets: list[dict[str, Any]]) -> list[dict[str, Any]]:
    """The sheets with every ``prose`` column re-expressed as Excel rich text.

    Only the .xlsx render goes through here. ``format=json`` keeps the stored Markdown, because
    its consumers (the web viewer, the manifest's .md files) render Markdown themselves — Excel
    is the one reader that cannot, and shows the asterisks instead.
    """
    rendered = []
    for sheet in sheets:
        prose = sheet["prose"]
        if not prose:
            rendered.append(sheet)
            continue
        rows = [
            [transcript_cell(value) if idx in prose else value for idx, value in enumerate(row)]
            for row in sheet["rows"]
        ]
        rendered.append({**sheet, "rows": rows})
    return rendered


@router.get("/report")
async def data_report(
    path: str = "",
    format: str | None = None,
    current_user: Any = Depends(require_dataset_downloader),
) -> Response:
    """Relational report of the subtree at ``path``: one sheet per record type, rows carrying
    their parent relations so the sheets interlink. ``format=json`` returns
    {sheets:[{name,color,columns,rows}]}; ``format=xlsx`` (default) streams a styled workbook.
    Every sheet is built from the caller's visible rows only."""
    fmt = (format or "xlsx").strip().lower()
    if fmt not in ("json", "xlsx"):
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail="format must be 'json' or 'xlsx'",
        )
    scope = await _scope_for(current_user)
    norm = _norm(path)
    segs = [s for s in norm.split("/") if s]
    records = await _report_records(segs, scope)
    sheets = _report_sheets(records)
    if fmt == "json":
        # THE VIEW. ``format=json`` is what the View Data page renders on screen — it hands back no
        # file — so a professor gets the design-workshop sheets here in full. The two flags travel
        # with them so the page can say, above the tables it is showing, that this account may read
        # them and not export them. Saying it is the requirement (``deps``' split, and the owner's
        # ruling behind it): a professor must be told, not handed a button that 403s.
        return JSONResponse(
            {
                "sheets": sheets,
                "designWorkshopsVisible": scope.design_workshops,
                "designWorkshopsDownloadable": scope.design_workshop_downloads,
            }
        )

    # THE DOWNLOAD. Design-workshop sheets are dropped for anybody but an admin, and the workbook is
    # still built — REFUSING THE WHOLE FILE WOULD BE THE WRONG SHAPE. A professor has always been
    # able to download this workbook; 403-ing it now would take away the seven legacy tables to
    # protect data that is not in them, which is a regression dressed as a permission.
    #
    # AND THE OMISSION IS WRITTEN INTO THE FILE, not only onto the screen it was downloaded from.
    # The web page says it beside the button (``designWorkshopsDownloadable`` above), but a workbook
    # outlives the page: it is archived, mailed on, and opened a year later by somebody who never saw
    # the sentence. A file that silently lacks a section it could have carried is the same failure as
    # a list that quietly stops. So the dropped block is replaced by ONE sheet that names it — and
    # only when there was something to drop, because a notice about data this account could never see
    # would be a permission it does not have, announced in a file it did not ask about.
    if not scope.design_workshop_downloads:
        dropped = [sheet for sheet in sheets if sheet.get("group") == DW_SHEET_GROUP]
        sheets = [sheet for sheet in sheets if sheet.get("group") != DW_SHEET_GROUP]
        if dropped:
            sheets.append(_dw_withheld_sheet(dropped))

    crumbs = await _resolve_crumbs(norm)
    level_name = crumbs[-1]["name"] if crumbs else "Repository"
    # openpyxl is sync; building a big workbook off-loop keeps requests flowing.
    payload = await asyncio.to_thread(
        build_report_workbook, _rendered(sheets), f"{level_name} report"
    )
    slug = re.sub(r"[^A-Za-z0-9]+", "-", level_name).strip("-").lower() or "repository"
    return StreamingResponse(
        io.BytesIO(payload),
        media_type=XLSX_MIME,
        headers={"Content-Disposition": _content_disposition(f"{slug}-report.xlsx")},
    )


def convert_ceiling_bytes() -> int:
    """The largest recording this box will convert in-process RIGHT NOW.

    `MAX_CONVERT_BYTES` bounded from above by what the box says is actually free — see
    `services/memory_budget`, and docs/SCALABILITY.md §5.1 fix 3 for why a constant alone was never
    the right shape. It can only ever return something at or below the constant, so a machine with
    memory to spare behaves exactly as it did, and a machine under pressure refuses sooner instead
    of dying with an OOM that takes every in-flight request with it.

    A FUNCTION AND NOT A CONSTANT because the answer changes between two requests a second apart,
    which is the whole point; and it reads `MAX_CONVERT_BYTES` at call time rather than closing over
    it, so lowering that module attribute (as `tests/test_media_convert_bound.py` does) still moves
    the ceiling the route enforces.
    """
    return memory_budget.budget_bytes(MAX_CONVERT_BYTES)


def _convert_audio_to_mp4(source_path: str) -> io.BytesIO:
    """Decode any uploaded audio container and re-encode as .mp4/AAC (sync; run off-loop).

    TAKES A PATH, NOT BYTES. Handed `io.BytesIO(raw)` pydub must be given the whole compressed input
    in the heap first, so the object was resident twice over before ffmpeg had decoded anything;
    given a path, ffmpeg opens the file itself and this process never holds the input at all. The
    OUTPUT is still a `BytesIO` — an .mp4 of a recording small enough to pass `convert_ceiling_bytes`
    is a few megabytes, and it is streamed straight out of here.
    """
    from pydub import AudioSegment

    segment = AudioSegment.from_file(source_path)
    out = io.BytesIO()
    segment.export(out, format="mp4")  # ffmpeg's mp4 muxer defaults to AAC audio
    out.seek(0)
    return out


@router.get("/media/{media_id}/download")
async def download_media(
    media_id: str,
    format: str | None = None,
    current_user: Any = Depends(require_dataset_downloader),
) -> Response:
    """Download one media file. Audio defaults to an .mp4 (AAC) conversion done server-side;
    anything else redirects to (or streams) the stored object untouched.

    The row must be one the caller can see: this route takes a bare id, so without the visibility
    check the whole media table would be readable by id even though the tree only ever lists the
    caller's own (and granted) uploads. An out-of-scope id reads as 404, never 403.
    """
    scope = await _scope_for(current_user)
    # Relations loaded so the Content-Disposition carries the same derived name the tree showed;
    # a file that arrives on disk under a different name than the one that was clicked is a bug.
    #
    # The name is the unnumbered one. The "-2" a duplicate picks up says "another file in the folder
    # you are extracting already answers to this", and a single download has no such folder — it
    # lands in Downloads, where the browser does its own numbering against whatever is already
    # there. Inventing a suffix here would mean guessing which of three taxonomies the click came
    # from, and each of them shows this file beside a different set of siblings.
    media = (
        await db.mediafile.find_first(
            where={"AND": [{"id": media_id}, scope.media]}, include=_NAMING_INCLUDE
        )
        if scope.media
        else await db.mediafile.find_unique(where={"id": media_id}, include=_NAMING_INCLUDE)
    )
    if media is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Media not found")

    is_audio = str(_ev(media.mediaType)) == "AUDIO"
    fmt = (format or "").strip().lower() or ("mp4" if is_audio else None)

    if is_audio and fmt == "mp4":
        try:
            import pydub  # noqa: F401 — local import: optional runtime dep (needs ffmpeg)
        except Exception as exc:  # pragma: no cover - environment-dependent
            raise HTTPException(
                status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
                detail="Audio conversion unavailable: pydub is not installed on the server.",
            ) from exc
        # THREE CHECKS, BECAUSE THE COLUMN IS A CLAIM AND THE LENGTH IS A FACT — and the fact is now
        # available, which it was not when this comment was first written and said "two".
        #
        # ``MediaFile.sizeBytes`` is whatever the client declared at ``POST /media/complete``: the
        # schema bounds it only from below (``Field(gt=0)``), it is stored verbatim, and nothing in
        # this codebase ever reconciles it against the stored object. Nor does the upload signature
        # bound the body: ``s3.presign_put_url`` signs Bucket/Key/ContentType and no
        # ``content-length-range``, and its docstring records why that must stay so (a signed
        # condition breaks every Android build already in the field). So an account holding
        # ``canDownloadDataset`` could presign an upload declaring ``sizeBytes: 1024``, PUT 1.5 GB
        # to the returned URL, complete it as AUDIO, and then click download on their OWN row.
        #
        # The declared size is kept as the CHEAP first refusal — it costs nothing, not even a round
        # trip, and rejects the honest large recording before anything is asked of storage. The real
        # length then comes from ``s3.head_object``, which is the follow-up this comment used to say
        # was outstanding: it reads ``ContentLength`` and no bytes, so an object that lied about its
        # size is refused BEFORE the fetch rather than after it. ``head_object`` answers ``None``
        # when storage will not say (a custom endpoint without ``HEAD``, a permission the API lacks),
        # and that is not treated as "small": ``download_to_temp``'s ``max_bytes`` counts what
        # actually lands and raises ``ObjectTooLarge`` mid-transfer, so the bound holds either way.
        #
        # AND THE READ ITSELF NO LONGER HAPPENS IN THE HEAP. ``download_to_temp`` streams the object
        # to a temp file in ranged chunks, so peak heap here is a chunk rather than the whole
        # recording, and ffmpeg opens that file directly instead of being handed a ``BytesIO`` of it.
        ceiling = convert_ceiling_bytes()
        declared = int(media.sizeBytes or 0)
        if declared > ceiling:
            raise HTTPException(
                status_code=status.HTTP_413_REQUEST_ENTITY_TOO_LARGE,
                detail="This recording is too large to convert in-process; download the original.",
            )
        head = await asyncio.to_thread(head_object, media.objectKey)
        if head is not None and head.size_bytes > ceiling:
            # Same answer as the declared-size refusal, deliberately: the caller asked for a
            # conversion of something too big to convert, and which of the two numbers caught it is
            # the server's business.
            raise HTTPException(
                status_code=status.HTTP_413_REQUEST_ENTITY_TOO_LARGE,
                detail="This recording is too large to convert in-process; download the original.",
            )
        try:
            # Blocking S3 transfer off the event loop — this is the single-worker web process.
            source_path = await asyncio.to_thread(
                download_to_temp, media.objectKey, suffix=".src", max_bytes=ceiling
            )
        except ObjectTooLarge as exc:
            raise HTTPException(
                status_code=status.HTTP_413_REQUEST_ENTITY_TOO_LARGE,
                detail="This recording is too large to convert in-process; download the original.",
            ) from exc
        except Exception as exc:
            raise HTTPException(
                status_code=status.HTTP_502_BAD_GATEWAY,
                detail="Could not fetch the audio bytes from object storage.",
            ) from exc
        try:
            # ffmpeg decode + AAC encode runs in a worker thread so requests keep flowing.
            out = await asyncio.to_thread(_convert_audio_to_mp4, source_path)
        except Exception as exc:
            raise HTTPException(
                status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
                detail=f"Audio conversion to mp4 failed (is ffmpeg installed?): {exc}",
            ) from exc
        finally:
            # The .mp4 is fully built in ``out`` by now, so the source is dead either way — and it
            # must go on the failure path too, or every ffmpeg error leaves a copy of the recording
            # on the disk this cap exists to protect.
            discard_temp(source_path)
        stem = display_stem(media, fallback=media.id)
        return StreamingResponse(
            out,
            media_type="video/mp4",
            headers={"Content-Disposition": _content_disposition(f"{stem}.mp4")},
        )

    # Non-audio (or an explicitly non-mp4 format): hand back the original object.
    if media.url:
        return RedirectResponse(media.url, status_code=status.HTTP_307_TEMPORARY_REDIRECT)
    # THIS FALLBACK HAD NO BOUND OF ANY KIND. It is reached whenever ``media.url`` is falsy — which
    # is every object stored without a public base URL — and it read the whole file into the heap
    # and passed it to ``Response(content=...)``, at any size, in the single-worker web process.
    # Streaming it off disk instead makes the size a DISK question rather than a RAM one, and
    # ``MAX_DOWNLOAD_BYTES`` is the bound on that; the 413 says so rather than truncating.
    try:
        source_path = await asyncio.to_thread(
            download_to_temp, media.objectKey, max_bytes=MAX_DOWNLOAD_BYTES
        )
    except ObjectTooLarge as exc:
        raise HTTPException(
            status_code=status.HTTP_413_REQUEST_ENTITY_TOO_LARGE,
            detail=(
                f"This file is {exc.size_bytes} bytes, over the {exc.limit_bytes}-byte limit this "
                f"server will spool for a download. Fetch it from object storage directly."
            ),
        ) from exc
    except Exception as exc:
        raise HTTPException(
            status_code=status.HTTP_502_BAD_GATEWAY,
            detail="Could not fetch the media bytes from object storage.",
        ) from exc
    name = display_filename(media, fallback=media.id)
    # ``background=`` and NOT a ``finally``: the file has to outlive this function, because
    # ``FileResponse`` has not read a byte of it yet — Starlette streams it while sending. The
    # background task runs after the last byte is on the wire, which is the only safe moment to
    # unlink it. Deleting it here would serve an empty download.
    return FileResponse(
        source_path,
        media_type=media.mimeType or "application/octet-stream",
        headers={"Content-Disposition": _content_disposition(name)},
        background=BackgroundTask(discard_temp, source_path),
    )

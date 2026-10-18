# The coverage map

`/map?view=coverage` on the web, for the ministry's readers (Assistant Director, Regional Director,
Ministry Admin, master admin). The Places view beside it is unchanged: it plots records where they
are. Coverage shades every district by how much of the programme has reached it and lists the gaps.

## What it shows

- **Per district** (or per state): design workshops held, workshops planned in the annual plan, field
  workshops, artisans, crafts, products, tools and media. Held and planned are never added together.
- **Markers** for design workshops, planned workshops, field workshops and named clusters, at the
  subject's pin where there is one and otherwise at a point inside the district. They fold into a
  count where they crowd. At most 500; the view says so past that.
- **Gaps**: shared crafts (`cross_portal_codes.json`) with no record under the filters; districts and
  states with no held workshop, grouped by state with "N of M".
- **Field Repository** (behind `CROSS_PORTAL_COVERAGE_ENABLED`, see [ENVIRONMENT.md](ENVIRONMENT.md)):
  its aggregate counts per district as a separate row, never summed into this portal's, because one
  artisan can exist in both without a consented link. A district counts as reached when either portal
  has a workshop there. Its documented crafts close a craft gap only within the reader's states (per-state
  codes), and not at all under a scheme filter. When the other server cannot be reached the map draws
  this portal only.
- **Filters**, all in the URL: craft (shared codes), scheme (`workshopKind` — field records carry none
  and drop out under a scheme filter), financial year (1 April to 31 March, IST; a plan row's
  `planYear` is its financial year), state, group by district or state, and what to shade by
  (workshops, artisans, all records).

A Regional Director sees the units of their assigned states and of the workshops they were named on;
an Assistant Director the states of their workshops. Ministry Admin and the master admin see the
country.

## The boundaries

The national outline is `frontend/components/map/indiaGeometry.ts`, verified point-in-polygon against
the Survey of India depiction (Aksai Chin, Gilgit-Baltistan and Muzaffarabad inside). It is drawn as
a neutral base fill and every district fill is clipped to it, so any part of Jammu and Kashmir or
Ladakh without a district polygon still reads as India. The caption under the map reads: "External
boundaries of India as depicted by Survey of India. District boundaries are indicative."

District polygons are `frontend/public/boundaries/district-shapes.json`, written by
`python scripts/build_boundaries.py --shapes` (needs `shapely`; build time only). The script clips
each polygon to the outline, simplifies at 0.01 degrees, takes a representative point that is inside
the polygon (not a plain centroid) and refuses to write when a check fails: a vertex more than
0.03 degrees outside the outline, a centroid outside its own polygon, a code the register does not
know, or Gilgit / Aksai Chin outside Ladakh-or-base-fill, Muzaffarabad outside J&K-or-base-fill. The
manifest records `shapesVersion` and the SHA-256 of the source. The server reads the same points from
`backend/app/data/boundaries/district-centroids.json`. `backend/tests/test_coverage_shapes.py` re-runs
every check on the committed file.

The source today is the Kaggle district set the border files already use. Survey of India's free
administrative boundary download sits behind a registered login; when it is obtained, point
`DISTRICTS_URL` at it and re-run. The 43 districts with no polygon are drawn inside the district they
were carved from (dashed outline, "shown inside … (parent)"); the three Delhi districts with no parent
are listed in the table only. Nothing is invented for them.

No tile server, no mapping library and no third-party request: the coverage bundle is held to that by
`frontend/e2e/coverage-unit.spec.ts`. (The record forms' location picker still uses MapTiler; that is
outside this view.)

## LGD codes

Every unit is keyed by its Local Government Directory code. `backend/app/data/lgd_register.json`
(built by `scripts/build_lgd_register.py`, byte-identical in both portals) codes all 36 states and
786 of the 795 districts; the 9 districts LGD did not list on the build date are under `pending`, with
the reason, and resolve to their state. Names equal `services/address.py`'s canonical names exactly.

`services/lgd.resolve` turns a free-text state and district into codes **when the map is read** — no
record table stores a code and nothing is backfilled. Order: the register's canonical names and
aliases, then an administrator's `GeoAlias`, then `place_atlas` over the free-text place. A real state
with a district of another state is `STATE_ONLY`, never the other state's district. Nothing is matched
fuzzily.

## Unmatched places

The master admin and the Ministry Admin see the spellings that did not reach a district
(`GET /api/map/coverage/unmatched`), busiest first, with a suggestion only when the district name is
an exact district of one other state. Mapping one (`POST /api/map/coverage/aliases`) applies to every
record written that way, is written to `RecordRevision` (`recordType` `geoalias`) and can be deleted.

## Endpoints

| Route | Who | Returns |
|---|---|---|
| `GET /api/map/coverage` | ministry readers | units (sparse), markers, gaps, catalogue, register and boundary versions, `truncated` past 20,000 rows per bucket. Cached 60 s per scope and filters. `peer=1` adds the Field Repository row |
| `GET /api/map/coverage/units/{stateLgd}/{districtLgd}/records` | ministry readers | one unit's records per bucket, 20 a page, names only; `districtLgd=0` is a state-level match |
| `GET /api/map/coverage/unmatched` | master admin, Ministry Admin | the unmatched spellings and the register to map them to |
| `POST` / `DELETE /api/map/coverage/aliases` | master admin, Ministry Admin | 201 / 409 / 422; 204 |

Stubs carry an id, a title, a link, a craft code and a financial year. Aadhaar, Pehchan, phone and
address text are never read into them.

## Android

The designer portal's handset has no coverage view: ministry surfaces are web-only
([DECISION-ministry-surfaces-web-only.md](DECISION-ministry-surfaces-web-only.md)).

## How this document is kept true

| Claim | Kept true by |
|---|---|
| Every register district has one LGD code or a `pending` entry; names equal `address.py`; both portals carry the same file and `lgd.py` | `test_lgd_register.py` |
| Resolution order, `STATE_ONLY`, `GEO_ALIAS`, `PLACE_ATLAS`, lineage `PARENT` / Delhi `STATE` | `test_lgd_resolve.py` |
| Counts, filters, the 1 April IST turn, gaps, sparse units, caps, no identity or address keys, the Field Repository row | `test_map_coverage.py` |
| Who reads the map and who maps a place; 409 / 422; the audit row | `test_map_coverage.py` (route tests) |
| The shapes decode, codes match the register, depiction points, outline tolerance, centroids inside | `test_coverage_shapes.py` and the build's own refusal |
| No tile server or mapping library in the coverage bundle; the caption | `frontend/e2e/coverage-unit.spec.ts` |
| The view, filters, district panel, gaps and unmatched flow | `frontend/e2e/map-coverage.spec.ts` |
| The variables are documented | [ENVIRONMENT.md](ENVIRONMENT.md) |

**Review triggers:** `services/address.py` (a new district or `DISTRICT_LIST_VERSION`),
`scripts/build_boundaries.py`, `services/record_filters.py`, `cross_portal_codes.json`, and the day
Survey of India district polygons are obtained.

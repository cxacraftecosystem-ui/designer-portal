"""Build the shared fixture workshop's report with the SERVER's code, for the browser's port to match.

The web can now build a workshop's report with no connection (``frontend/lib/offlineReport``): the
same traversal as ``report_builder.ReportBuilder``, written again in TypeScript because a browser
cannot run this module. Two copies of one traversal drift, and the drift this guards against is not
a crash — it is a heading numbered differently, a table column rounded the other way or a warning
worded apart, in two files a ministry could receive for one workshop.

So this tool runs the server's own path over ``shared/report-parity/workshop.json`` — every case
in that file, through ``report_meta``, ``resolve_accent``, ``apply_report_settings`` and
``build_report`` exactly as ``_build_only`` (the preview) and ``render_report`` (the file) call them
— and writes what came out to ``shared/report-parity/expected.json``.
``frontend/e2e/offline-report-parity-unit.spec.ts`` builds the same cases in TypeScript and
compares block for block. ``tests/test_report_offline_parity.py`` re-runs this tool and fails if the
committed file is stale, so a change to the server's builder cannot land without the browser's
copy being made to agree in the same change.

It also writes ``shared/report-gazetteer.json``: the closed state and district lists, the place
atlas and the state seats the map section places an address with (``report_builder._geocode``),
exported rather than re-typed so the browser geocodes against the same tables.

    cd backend && python tools/report_offline_parity.py           # check, exit 1 if stale
    cd backend && python tools/report_offline_parity.py --write   # regenerate both files

Nothing imports this at runtime; it is a development tool and lives outside ``app/``.
"""

from __future__ import annotations

import json
import os
import sys
from dataclasses import asdict, replace
from pathlib import Path
from types import SimpleNamespace
from typing import Any

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

# The settings object refuses to build without these, and nothing this tool runs reads any of them:
# it builds documents in memory and never opens a connection. Placeholders, set only when absent —
# and NEVER under pytest, where `tests/conftest.py` owns the environment and twenty-eight modules
# decide whether a database exists by reading `DATABASE_URL`; a placeholder there would send them
# looking for one.
_PLACEHOLDERS = {
    "DATABASE_URL": "postgresql://unused:unused@unused.invalid:5432/unused",
    "JWT_SECRET": "x" * 64,
    "AWS_ACCESS_KEY_ID": "unused",
    "AWS_SECRET_ACCESS_KEY": "unused",
    "AWS_S3_BUCKET": "unused",
    "MASTER_ADMIN_EMAIL": "unused@example.invalid",
}
if "pytest" not in sys.modules:
    for _name, _value in _PLACEHOLDERS.items():
        os.environ.setdefault(_name, _value)

import app.services.stage_definitions  # noqa: E402,F401  - installs the registry
from app.services import address, geography, place_atlas  # noqa: E402
from app.services.custom_sections import (  # noqa: E402
    CustomDefinition,
    CustomFieldSpec,
    CustomOption,
    CustomSectionSpec,
)
from app.services.design_workshops import (  # noqa: E402
    report_custom_section_items,
    report_meta,
    resolve_template_id,
)
from app.services.report_ai_layers import AiLayerItem, attach_ai_layers  # noqa: E402
from app.services.report_annexures import TranscriptItem, attach_transcripts  # noqa: E402
from app.services.report_builder import ReferencedRecord, WorkshopData, build_report  # noqa: E402
from app.services.report_custom_sections import (  # noqa: E402
    attach_custom_sections,
    custom_sections_of,
)
from app.services.report_model import ImageRef, PageSize  # noqa: E402
from app.services.report_questionnaires import (  # noqa: E402
    QuestionnaireAnswer,
    QuestionnaireItem,
    QuestionnaireSitting,
    attach_questionnaires,
)
from app.services.report_templates import (  # noqa: E402
    SpecialSection,
    apply_report_settings,
    inert_section_toggles,
    template as get_template,
)
from app.services.report_theme import resolve_accent, resolve_font, theme_from_accent  # noqa: E402
from app.services.stage_schema import FieldType, Tier, stages  # noqa: E402
from app.services.workshop_transcripts import wants_transcripts  # noqa: E402

REPO = Path(__file__).resolve().parents[2]
FIXTURE = REPO / "shared" / "report-parity" / "workshop.json"
EXPECTED = REPO / "shared" / "report-parity" / "expected.json"
GAZETTEER = REPO / "frontend" / "lib" / "offlineReport" / "gazetteer.json"


def _definition(payload: dict[str, Any] | None) -> CustomDefinition:
    """The custom-section definition from its API shape (``custom_sections.definition_payload``)."""
    if not payload:
        return CustomDefinition()
    sections = []
    for s in payload.get("sections") or []:
        fields = tuple(
            CustomFieldSpec(
                key=f["key"],
                label=f["label"],
                type=FieldType(f["type"]),
                tier=Tier(f["tier"]),
                required=bool(f["required"]),
                unit=f.get("unit") or "",
                options=tuple(
                    CustomOption(value=o["value"], label=o.get("label") or "")
                    for o in f.get("options") or []
                ),
                sort_order=int(f.get("sortOrder") or 0),
                retired=bool(f.get("retired")),
                id=f.get("id") or "",
            )
            for f in s.get("fields") or []
        )
        sections.append(
            CustomSectionSpec(
                key=s["key"],
                title=s["title"],
                stage_key=s["stageKey"],
                description=s.get("description") or "",
                sort_order=int(s.get("sortOrder") or 0),
                fields=fields,
                retired=bool(s.get("retired")),
                id=s.get("id") or "",
            )
        )
    return CustomDefinition(sections=tuple(sections), version=payload.get("customSchemaVersion", ""))


def _questionnaires(items: list[dict[str, Any]]) -> list[QuestionnaireItem]:
    out = []
    for item in items:
        sittings = tuple(
            QuestionnaireSitting(
                **{k: v for k, v in s.items() if k != "answers"},
                answers=tuple(QuestionnaireAnswer(**a) for a in s.get("answers") or []),
            )
            for s in item.get("sittings") or []
        )
        out.append(QuestionnaireItem(**{k: v for k, v in item.items() if k != "sittings"}, sittings=sittings))
    return out


def _data(fixture: dict[str, Any], settings: dict[str, Any]) -> WorkshopData:
    """``assemble_workshop_data`` over the fixture's stages: one singleton per stage, rows by entity."""
    singletons: dict[str, dict[str, Any]] = {}
    collections: dict[str, dict[str, list[dict[str, Any]]]] = {}
    for stage_key, stage in fixture["stages"].items():
        if stage.get("singleton"):
            singletons[stage_key] = dict(stage["singleton"])
        for entity_key, rows in (stage.get("collections") or {}).items():
            if rows:
                collections.setdefault(stage_key, {})[entity_key] = [dict(r) for r in rows]
    report = dict(singletons.get("REPORT_GENERATION") or {})
    report.update(settings)
    singletons["REPORT_GENERATION"] = report
    data = WorkshopData(
        workshop_id=fixture["workshop"]["id"],
        title=fixture["workshop"]["title"],
        singletons=singletons,
        collections=collections,
        generated_at=fixture["generatedAt"],
    )
    sources = fixture["sources"]
    data.references = {k: ReferencedRecord(**v) for k, v in sources["references"].items()}
    data.district_points = {k: (v[0], v[1]) for k, v in sources["districtPoints"].items()}
    return data


def build_case(fixture: dict[str, Any], case: dict[str, Any]) -> dict[str, Any]:
    """One case, built the way ``_build_only`` (PREVIEW) or ``render_report`` (FILE) builds it."""
    sources = fixture["sources"]
    options = dict(case.get("options") or {})
    data = _data(fixture, case.get("settings") or {})
    settings = data.singleton("REPORT_GENERATION")
    record = SimpleNamespace(**fixture["workshop"])
    is_file = case["kind"] == "FILE"

    template_id = resolve_template_id(options.get("templateId"), settings, record)
    base = get_template(template_id)
    specials = {s.special for s in base.sections}

    # The loads, in `_report_inputs`' order, each warning as that load warns.
    load_warnings: list[str] = []
    if SpecialSection.ANNEXURE_QUESTIONNAIRES in specials:
        attach_questionnaires(data, _questionnaires(sources["questionnaires"]))
        load_warnings.extend(sources["warnings"]["questionnaires"])
    if wants_transcripts(options.get("includeTranscripts") if is_file else None, settings):
        attach_transcripts(data, [TranscriptItem(**t) for t in sources["transcripts"]])
        load_warnings.extend(sources["warnings"]["transcripts"])
    include_ai = bool(options.get("includeAiLayers")) if is_file else False
    if include_ai:
        attach_ai_layers(data, [AiLayerItem(**a) for a in sources["aiLayers"]])
        load_warnings.extend(sources["warnings"]["aiLayers"])
    values_by_stage = {
        key: dict(stage["custom"]) for key, stage in fixture["stages"].items() if stage.get("custom")
    }
    items, custom_warnings = report_custom_section_items(
        _definition(fixture.get("customDefinition")), values_by_stage
    )
    attach_custom_sections(data, items)
    load_warnings.extend(custom_warnings)
    load_warnings.extend(sources["warnings"]["media"])
    load_warnings.extend(inert_section_toggles(base, settings))

    media = {k: ImageRef(**v) for k, v in sources["media"].items()}

    meta = replace(report_meta(record, template_id, settings), generated_at=fixture["generatedAt"])
    if is_file and options.get("pageSize"):
        meta = replace(meta, page_size=PageSize(options["pageSize"]))
    if is_file and options.get("headerText"):
        meta = replace(meta, header_text=options["headerText"])
    if is_file and options.get("footerText"):
        meta = replace(meta, footer_text=options["footerText"])
    accent = resolve_accent(options.get("themeAccent") if is_file else None, settings)
    theme = theme_from_accent(accent, base=base.theme) if accent else base.theme
    fonts = resolve_font(options.get("fontPreset") if is_file else None, settings)
    if fonts:
        theme = replace(theme, heading_font=fonts[0], body_font=fonts[1])
    shaped = apply_report_settings(
        base,
        settings,
        include_photographs=options.get("includePhotographs") if is_file else None,
        include_ai_layers=include_ai if is_file else None,
        custom_sections=custom_sections_of(data),
    )
    document, warnings = build_report(
        data, template_id, media.get, meta=meta, theme=theme, template=shaped
    )
    return {
        "meta": _plain(asdict(document.meta)),
        "theme": asdict(document.theme),
        "blocks": [_block(b) for b in document.blocks],
        "warnings": list(warnings) + load_warnings,
    }


def _plain(value: Any) -> Any:
    """``asdict`` output made JSON: enums to their values, tuples to lists, sets sorted."""
    if isinstance(value, dict):
        return {k: _plain(v) for k, v in value.items()}
    if isinstance(value, (list, tuple)):
        return [_plain(v) for v in value]
    if isinstance(value, (set, frozenset)):
        return sorted(_plain(v) for v in value)
    if hasattr(value, "value") and not isinstance(value, (str, int, float, bool)):
        return value.value
    if isinstance(value, str) and type(value) is not str:  # a str Enum
        return str(value.value)  # type: ignore[attr-defined]
    return value


def _block(block: Any) -> dict[str, Any]:
    """``_block_payload`` in the route: the type name, then the dataclass's fields."""
    payload: dict[str, Any] = {"type": type(block).__name__.replace("Block", "").upper()}
    payload.update(_plain(asdict(block)))
    return payload


def gazetteer() -> dict[str, Any]:
    """The tables ``report_builder._geocode`` reads, exported for the browser's port of it."""
    return {
        "_comment": (
            "Generated by backend/tools/report_offline_parity.py from services/address.py, "
            "services/place_atlas.py and services/geography.py. Do not edit by hand."
        ),
        "states": list(address.INDIAN_STATES_AND_UNION_TERRITORIES),
        "stateLookup": dict(address._LOOKUP),
        "districtsByState": {k: list(v) for k, v in address.DISTRICTS_BY_STATE.items()},
        "districtLookup": {k: dict(v) for k, v in address._DISTRICT_LOOKUP.items()},
        "districtSuffixes": list(address._DISTRICT_SUFFIXES),
        "stateSeats": {k: list(v) for k, v in geography.STATE_SEATS.items()},
        "atlasStateSeats": {k: list(v) for k, v in place_atlas._STATE_SEATS.items()},
        "atlasStateAliases": dict(place_atlas._EXTRA_STATE_ALIASES),
        "maxAliasWords": place_atlas._MAX_ALIAS_WORDS,
        "places": [
            {
                "key": p.key,
                "label": p.label,
                "region": p.region,
                "state": p.state,
                "lat": p.latitude,
                "lon": p.longitude,
                "precision": p.precision.value,
                "aliases": list(p.aliases),
                "district": p.district,
            }
            for p in place_atlas._PLACES
        ],
        "atlasDistrictAnchors": _atlas_anchors(),
    }


def _atlas_anchors() -> dict[str, list[float]]:
    """``DistrictAnchors.seed_from_atlas`` alone — what the map has before any record is learned."""
    anchors = geography.DistrictAnchors()
    anchors.seed_from_atlas()
    out: dict[str, list[float]] = {}
    for state, districts in address.DISTRICTS_BY_STATE.items():
        for district in districts:
            point = anchors.anchor(state, district)
            if point:
                out[geography.district_key(state, district)] = [point[0], point[1]]
    return out


def expected() -> dict[str, Any]:
    fixture = json.loads(FIXTURE.read_text(encoding="utf-8"))
    return {
        "_comment": (
            "Generated by backend/tools/report_offline_parity.py from workshop.json. "
            "Do not edit by hand."
        ),
        "registryStages": [s.key for s in stages()],
        "cases": {case["name"]: build_case(fixture, case) for case in fixture["cases"]},
    }


def _dump(value: Any) -> str:
    return json.dumps(value, ensure_ascii=False, indent=1, sort_keys=False) + "\n"


def main(argv: list[str]) -> int:
    write = "--write" in argv
    stale = []
    for path, value in ((EXPECTED, expected()), (GAZETTEER, gazetteer())):
        text = _dump(value)
        if write:
            path.write_text(text, encoding="utf-8", newline="\n")
            print(f"wrote {path.relative_to(REPO)}")
        elif not path.exists() or path.read_text(encoding="utf-8") != text:
            stale.append(str(path.relative_to(REPO)))
    if stale:
        print("stale: " + ", ".join(stale) + " — run tools/report_offline_parity.py --write")
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))

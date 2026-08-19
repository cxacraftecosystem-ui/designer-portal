"""The three things a generated report contains that are not text: the map, the infographics and
the photographs that are placed beside what they are photographs of.

Every assertion here defends the same property, which is the only property that matters for a
document a ministry reads as fact: A FIGURE IS DRAWN FROM WHAT WAS RECORDED OR IT IS NOT DRAWN.
There is no placeholder chart, no zero-filled category and no invented pin. A donut reading
"0 selected, 0 rejected, 0 pending" is not an empty figure — it is a claim that nothing was
selected, and no reader can tell that apart from a stage nobody filled in.

The other half is the mirror of it and just as expensive: a figure that quietly VANISHES. A map
that disappears whenever the village names are unfamiliar reads to a designer as a broken
renderer, and a photograph that sits in the media table one join away from the prototype it
belongs to is data the designer captured and the report never showed. Both of those shipped
once; every test below names the one it prevents.
"""

import app.services.stage_definitions  # noqa: F401  - installs the registry
from app.services.report_builder import (
    FIGURES,
    MAP_ROSTER_ENTITY,
    MAP_ROSTER_PIN_KEY,
    MAP_ROSTER_PLACE_KEYS,
    MAP_ROSTER_STAGE,
    MAP_ROSTER_STATE_KEY,
    ReferencedRecord,
    ReportBuilder,
    WorkshopData,
    build_report,
)
from app.services.report_model import (
    ChartBlock,
    ImageBlock,
    ImageGridBlock,
    ImageRef,
    MapBlock,
    MapPointKind,
    ReportMeta,
)
from app.services.report_templates import TEMPLATES, SpecialSection, template
from app.services.stage_schema import FieldType, stages

# --------------------------------------------------------------------------------------
# Fixtures
# --------------------------------------------------------------------------------------


def _meta(**kw) -> ReportMeta:
    base = {"title": "Workshop", "subtitle": "Cluster", "generated_at": "2026-08-07T00:00:00Z"}
    base.update(kw)
    return ReportMeta(**base)


def _data(**kw) -> WorkshopData:
    base = {"workshop_id": "w1", "title": "Workshop"}
    base.update(kw)
    return WorkshopData(**base)


def _resolver(media_id: str) -> ImageRef:
    return ImageRef(source=media_id, width_px=800, height_px=600, mime_type="image/jpeg")


def _build(data: WorkshopData, template_id: str = "DETAILED_TECHNICAL"):
    document, _warnings = build_report(data, template_id, _resolver, meta=_meta())
    return document


def _all_text(document) -> str:
    """Every character the document would print, whatever block kind carries it.

    Walks runs generically rather than naming block types, so a table cell, a heading, a key-value
    pair and a card caption are all searched. A helper that only read tables is how an earlier
    version of a grouping test passed against a report that was actually wrong.
    """
    out: list[str] = []

    def harvest(value) -> None:
        if isinstance(value, str):
            out.append(value)
            return
        if isinstance(value, (list, tuple)):
            for item in value:
                harvest(item)
            return
        text = getattr(value, "text", None)
        if isinstance(text, str):
            out.append(text)
        for slot in getattr(value, "__slots__", ()) or ():
            if slot != "text":
                harvest(getattr(value, slot, None))

    for block in document.blocks:
        harvest(block)
    return " | ".join(out)


def _maps(document) -> list[MapBlock]:
    return [b for b in document.blocks if isinstance(b, MapBlock)]


def _charts(document) -> list[ChartBlock]:
    return [b for b in document.blocks if isinstance(b, ChartBlock)]


def _chart(document, title_fragment: str) -> ChartBlock:
    found = [c for c in _charts(document) if title_fragment.lower() in c.title.lower()]
    assert found, (
        f"no chart titled like {title_fragment!r}; the document has "
        f"{[c.title for c in _charts(document)]}"
    )
    return found[0]


# --------------------------------------------------------------------------------------
# The catalogue: a template may only name a figure that exists
# --------------------------------------------------------------------------------------


def test_every_figure_a_template_names_exists_in_the_catalogue():
    """A typo in a template's ``figures`` tuple has no other way to announce itself.

    ``report_templates`` deliberately holds the figure ids as plain strings — it must not import
    the builder, because the builder imports it — so nothing at import time checks that
    ``"COST_BY_HEAD"`` is spelled the way the catalogue spells it. Without this test the only
    symptom of a misspelling is a figure that silently never prints, in a sixty-page document
    nobody proof-reads against the data.
    """
    for report_template in TEMPLATES:
        for section in report_template.sections:
            for figure_id in section.figures:
                assert figure_id in FIGURES, (
                    f"{report_template.id} asks for figure {figure_id!r}, which "
                    f"report_builder.FIGURES does not declare"
                )


def test_every_catalogued_figure_has_a_builder_method():
    """``_figure`` reaches its builder by ``getattr``, so a renamed method is an AttributeError
    at render time rather than at import — a 500 on the download button of a finished workshop."""
    builder = ReportBuilder(_data(), template("DCH_STANDARD"), _resolver, meta=_meta())
    for figure_id, (stage_key, method_name) in FIGURES.items():
        assert callable(getattr(builder, method_name, None)), \
            f"{figure_id} names {method_name}, which ReportBuilder does not have"
        assert stage_key, f"{figure_id} declares no owning stage"


def test_the_full_templates_carry_a_map_and_a_chart_section():
    """The two templates a ministry actually receives. A figure nothing places is a figure that
    does not exist, and the placement lives here rather than in the builder on purpose."""
    for template_id in ("DCH_STANDARD", "DETAILED_TECHNICAL"):
        specials = [s.special for s in template(template_id).sections]
        assert SpecialSection.MAP in specials, f"{template_id} places no map"
        assert SpecialSection.CHART in specials, f"{template_id} places no figures"


def test_the_map_follows_the_participant_roster():
    """Position IS the figure's argument. Printed under the table of thirty names the map answers
    the question that table raises; printed anywhere else it is a decorative map of India."""
    for template_id in ("DCH_STANDARD", "DETAILED_TECHNICAL"):
        sections = template(template_id).sections
        roster = next(i for i, s in enumerate(sections)
                      if s.stage_key == "WORKSHOP_PLAN_PARTICIPANTS_OPENING")
        map_at = next(i for i, s in enumerate(sections) if s.special is SpecialSection.MAP)
        assert map_at == roster + 1, f"{template_id} does not print the map after the roster"


def test_the_buyer_catalogue_never_prints_the_cost_breakdown():
    """PHOTO_CATALOGUE goes to the person negotiating against the cluster, and the cost-by-head
    figure prints the maker's material and labour cost beside the price they are being quoted."""
    catalogue = template("PHOTO_CATALOGUE")
    costing = catalogue.section_for("COSTING_MARKET_LINKAGE")
    assert costing is not None and costing.include_figures is False
    assert not any(s.special is SpecialSection.CHART for s in catalogue.sections)


# --------------------------------------------------------------------------------------
# The map
# --------------------------------------------------------------------------------------


def test_no_state_and_no_district_means_no_map_at_all():
    """A map of India with no idea which part of it the workshop happened in is a decoration.

    Not an empty frame and not a heading over nothing: the whole section is absent, because a
    heading with a blank rectangle under it is the single most common way a generated report
    looks broken to the officer who opens it.
    """
    document = _build(_data(singletons={"WORKSHOP_SETUP": {"workshopTitle": "W",
                                                           "venue": "Community hall"}}))
    assert _maps(document) == []


def test_a_map_with_no_points_still_renders():
    """THE REGRESSION THIS FILE EXISTS FOR. Stage 1 named Odisha and nothing in the record could
    be geocoded — no venue address, no artisan whose village this build's atlas knows.

    The block is emitted anyway, with an empty point tuple, because the tinted state IS the
    figure in that case: the record genuinely says "this workshop happened in Odisha" and the
    map says exactly that and no more. An earlier build skipped the section whenever nothing
    resolved, which a designer reads as a broken renderer rather than as unresolvable data, and
    which no test would have caught because a missing figure raises nothing.
    """
    document = _build(_data(singletons={"WORKSHOP_SETUP": {"state": "Odisha"}}))
    maps = _maps(document)
    assert len(maps) == 1
    block = maps[0]
    assert block.points == ()
    assert "Odisha" in block.highlight
    assert "could be resolved" in block.caption, \
        "the caption must say why the map has no pins, or the reader assumes a rendering fault"


def test_a_district_alone_is_enough_to_draw_the_map():
    """Stage 1 offers state and district separately and a designer may fill in either first."""
    document = _build(_data(singletons={"WORKSHOP_SETUP": {"district": "Bargarh"}}))
    assert len(_maps(document)) == 1


def test_a_measured_venue_fix_wins_over_the_typed_address():
    """``venueLocation`` is a coordinate somebody stood at; the address is a name looked up in a
    table. Preferring the lookup would move the venue to the district headquarters for every
    workshop held in a village, which is most of them."""
    document = _build(_data(singletons={"WORKSHOP_SETUP": {
        "state": "Odisha", "district": "Bargarh", "venue": "Weavers' Service Centre",
        "venueLocation": {"lat": 21.33331, "lon": 83.61672},
    }}))
    venue = next(p for p in _maps(document)[0].points if p.kind is MapPointKind.VENUE)
    assert (round(venue.lat, 5), round(venue.lon, 5)) == (21.33331, 83.61672)


def test_a_zero_zero_fix_falls_back_to_the_address_rather_than_the_gulf_of_guinea():
    """``0, 0`` is what a form that never obtained a fix writes. Rejecting it here is what keeps
    the venue on the map at all: the address still resolves to the right state."""
    document = _build(_data(singletons={"WORKSHOP_SETUP": {
        "state": "Rajasthan", "village": "Bagru", "venueLocation": {"lat": 0.0, "lon": 0.0},
    }}))
    venue = next(p for p in _maps(document)[0].points if p.kind is MapPointKind.VENUE)
    assert (round(venue.lat, 3), round(venue.lon, 3)) == (26.815, 75.545)


def test_every_roster_map_key_is_a_real_participant_field():
    """``MAP_ROSTER_PLACE_KEYS`` named ``block`` for a long time, which is a ``workshopSetup``
    field and not a participant one, so a third of the map's roster read had never returned
    anything but ``None``.

    Nothing catches that at import and nothing can. ``validate_registry`` does check
    ``caption_for``, ``ref_filter_by``, ``label_field``, promoted paths and hydration targets
    against real fields — but it lives in ``stage_schema``, which ``report_builder`` imports, so it
    cannot see these constants without an import cycle. The check has to be a test instead.
    """
    entity = next(e for s in stages() if s.key == MAP_ROSTER_STAGE
                  for e in s.entities if e.key == MAP_ROSTER_ENTITY)
    for key in (*MAP_ROSTER_PLACE_KEYS, MAP_ROSTER_STATE_KEY, MAP_ROSTER_PIN_KEY):
        assert entity.field(key) is not None, (
            f"report_builder reads {MAP_ROSTER_ENTITY}.{key} for the map, and the registry "
            f"declares no such field — that read is dead and silently contributes nothing"
        )
    assert entity.field(MAP_ROSTER_PIN_KEY).type is FieldType.GEO, \
        "the roster pin key must name a coordinate field, or _geo_point will refuse every row"


def test_the_map_reads_the_roster_row_s_own_frozen_address():
    """THE ROW DECIDES. ``REFERENCE_HYDRATION["participant.artisanRef"]`` copies village,
    district, state, pincode and address onto the roster row at save time, so the frozen copy the
    participant table prints is sitting right beside the ref — and the map must print the same
    answer that table does, from the same bytes.

    The row here has no ``artisanRef`` at all, which is also the hand-typed case: a participant
    who walked in on day two, whose village the designer wrote down themselves.
    """
    document = _build(_data(
        singletons={"WORKSHOP_SETUP": {"state": "Odisha", "district": "Bargarh"}},
        collections={"WORKSHOP_PLAN_PARTICIPANTS_OPENING": {"participant": [
            {"serialNo": 1, "name": "One", "village": "Barpali", "district": "Bargarh",
             "state": "Odisha"},
        ]}},
    ))
    homes = [p for p in _maps(document)[0].points if p.kind is MapPointKind.ARTISAN]
    assert [p.label for p in homes] == ["Barpali"]


def test_a_live_artisan_record_never_moves_a_pin_the_roster_row_has_already_frozen():
    """THE ONE PLACE A SUBMITTED REPORT RE-RESOLVED A LIVE ROW, and this is the test for it.

    ``_artisan_points`` used to build every pin from ``self.data.reference(...)`` first and read
    the row's own keys only ``if not text``. ``WorkshopData.references`` is filled by
    ``design_workshops.load_report_references``, which issues an unqualified ``find_many`` against
    the CURRENT ``Artisan`` table at render time and reads the district off the live ``Location``.
    So a researcher correcting an artisan's address in June — or a co-designer merging the record
    into a duplicate — moved a pin in a February report that had already been handed to an
    officer, while the participant table two pages earlier went on printing the frozen copy. One
    document, two answers about where one person lives, and nothing on the page to say why.

    It never even needed an edit: hydration is only-fill-blanks, so a designer who OVERTYPED the
    roster row's district got the same disagreement on the day they typed it.

    The fixture makes the disagreement total — the row froze Barpali in Odisha, the live record
    now says Bhuj in Gujarat — so restoring the old precedence fails both assertions rather than
    shifting a pin by a few kilometres.
    """
    document = _build(_data(
        singletons={"WORKSHOP_SETUP": {"state": "Odisha", "district": "Bargarh"}},
        collections={"WORKSHOP_PLAN_PARTICIPANTS_OPENING": {"participant": [
            {"serialNo": 1, "name": "One", "artisanRef": "a1", "village": "Barpali",
             "district": "Bargarh", "state": "Odisha"},
        ]}},
        references={"a1": ReferencedRecord(model="Artisan", label="One", place="Bhuj",
                                           district="Kachchh", state="Gujarat")},
    ))
    block = _maps(document)[0]
    homes = [p for p in block.points if p.kind is MapPointKind.ARTISAN]
    assert [p.label for p in homes] == ["Barpali"], \
        "the map printed the live record's district where the table beside it prints the frozen one"
    assert "Gujarat" not in block.highlight, \
        "a state nothing in the submitted record names was tinted because a live row was re-read"


def test_a_roster_row_that_states_no_address_falls_back_to_the_referenced_artisan_record():
    """Rows saved before the artisan hydration mapping widened carry no stated address at all, and
    for those the ``Artisan`` record is still the only place the address exists. That is why
    ``ReferencedRecord.place/district/state`` are kept and why deleting them would drop those rows
    off the map.

    THIS TEST WAS ``test_artisan_homes_come_from_the_referenced_artisan_record`` AND ITS DOCSTRING
    PINNED A BUG. It asserted the reference-first precedence as though it were the design, on the
    stated ground that "No roster field holds a district. The participant row records a village as
    free text" — which stopped being true when ``REFERENCE_HYDRATION["participant.artisanRef"]``
    began copying village, district, state, pincode and address onto the row. The fixture is
    unchanged, because a row with no stated address is exactly what the fallback is for; only the
    claim about the tree is corrected, and the row-first case is asserted above it.
    """
    document = _build(_data(
        singletons={"WORKSHOP_SETUP": {"state": "Rajasthan", "district": "Jaipur"}},
        collections={"WORKSHOP_PLAN_PARTICIPANTS_OPENING": {"participant": [
            {"serialNo": 1, "name": "One", "artisanRef": "a1"},
            {"serialNo": 2, "name": "Two", "artisanRef": "a2"},
        ]}},
        references={
            "a1": ReferencedRecord(model="Artisan", label="One", place="Bagru",
                                   district="Jaipur", state="Rajasthan"),
            "a2": ReferencedRecord(model="Artisan", label="Two", place="Sanganer",
                                   district="Jaipur", state="Rajasthan"),
        },
    ))
    homes = [p for p in _maps(document)[0].points if p.kind is MapPointKind.ARTISAN]
    assert {p.label for p in homes} == {"Bagru", "Sanganer"}


def test_the_row_s_own_district_and_state_are_both_read_not_just_the_village():
    """TWO FIXES IN ONE FIXTURE, because they fail together.

    The row-side read used to take the FIRST non-empty of ``MAP_ROSTER_PLACE_KEYS`` and never
    looked at the row's ``state`` at all — the state came from stage 1, always. So an artisan who
    travelled in from the next state, and whose hamlet this build's atlas has never heard of, was
    geocoded as "hamlet, the workshop's state": the district they actually named was never
    consulted and neither was the state they actually named, and the pin landed on the WORKSHOP's
    state capital. Joining the row's village and district the way ``_venue_point`` joins the
    venue's address, and reading the row's own state before stage 1's, is what places them.

    The village here is invented on purpose. A hamlet the atlas knows would resolve from its own
    name whatever state was passed, and the test would pass against the old code by luck.
    """
    document = _build(_data(
        singletons={"WORKSHOP_SETUP": {"state": "Rajasthan", "district": "Jaipur"}},
        collections={"WORKSHOP_PLAN_PARTICIPANTS_OPENING": {"participant": [
            {"serialNo": 1, "name": "Visitor", "village": "Kusumpur Tola",
             "district": "Bargarh", "state": "Odisha"},
        ]}},
    ))
    block = _maps(document)[0]
    assert "Odisha" in block.highlight, (
        "the artisan's pin landed in the workshop's state; the district and state frozen on their "
        "own roster row were never read"
    )
    home = next(p for p in block.points if p.kind is MapPointKind.ARTISAN)
    assert home.label != "Rajasthan"


def test_a_row_that_froze_a_village_but_no_state_is_placed_from_the_record_not_the_workshop():
    """THE ROW SHAPE THE ROW-FIRST PRECEDENCE PINNED 1,300 KM AWAY, and the reason the legacy gate
    asks about the STATE and not only about the address text.

    ``"village": "village"`` has been in ``REFERENCE_HYDRATION["participant.artisanRef"]`` since the
    initial commit; ``district`` and ``state`` were added much later. A roster row saved in between
    carries a village and NOTHING that can place it — so while the gate read ``if not text`` those
    rows took the row-first branch, never reached their ``Artisan`` record, fell through to the
    WORKSHOP's state, and were geocoded as "hamlet, Rajasthan": one pin on Jaipur, with Odisha not
    tinted at all. A row carrying MORE frozen data drew a worse pin than one carrying none — delete
    the ``village`` key from this fixture and the old code placed it correctly.

    The village is invented on purpose. A hamlet the atlas knows would resolve from its own name
    whatever state was passed and the test would pass against the old code by luck.
    """
    document = _build(_data(
        singletons={"WORKSHOP_SETUP": {"state": "Rajasthan", "district": "Jaipur"}},
        collections={"WORKSHOP_PLAN_PARTICIPANTS_OPENING": {"participant": [
            {"serialNo": 1, "name": "One", "artisanRef": "a1", "village": "Kusumpur Tola"},
        ]}},
        references={"a1": ReferencedRecord(model="Artisan", label="One",
                                           district="Bargarh", state="Odisha")},
    ))
    block = _maps(document)[0]
    assert "Odisha" in block.highlight, (
        "a roster row that froze a village but no state was geocoded against the WORKSHOP's state, "
        "so the artisan's own state was never tinted"
    )
    home = next(p for p in block.points if p.kind is MapPointKind.ARTISAN)
    assert home.label == "Bargarh", (
        f"the pin is labelled {home.label!r}: the row's village was placed against the workshop's "
        f"state instead of the district and state the artisan record states"
    )
    assert round(home.lat, 1) != 26.9 or round(home.lon, 1) != 75.8, \
        "the pin is sitting on Jaipur, the workshop's state capital"


def test_a_pin_dropped_on_the_artisan_s_own_place_is_drawn_where_it_was_dropped():
    """``participant.subjectLocation`` is the pin a researcher dropped on the artisan's OWN place,
    hydrated onto the roster row at save time — and no renderer read it.

    The map geocoded the district NAME instead, so a report holding an exact coordinate for an
    artisan drew them on a district centroid or, where the atlas knew only the state, on the state
    capital — and then counted them in the caption's "drawn at its state capital" sentence, exactly
    as if no pin had ever been dropped. The pin did print, as a raw "21.20411, 83.60122" key-value
    line under the participant table, where no reader can use it.

    The fixture's coordinate is deliberately NOT Barpali's atlas position (21.1918, 83.5906), so a
    fix that quietly went on geocoding the village name fails on the numbers rather than passing by
    coincidence. It is the STATED pin: ``_subject_point`` is the only coordinate allowed to cross
    onto a stage entry, because the device's own fix is the desk the record was typed at.
    """
    document = _build(_data(
        singletons={"WORKSHOP_SETUP": {"state": "Odisha", "district": "Bargarh"}},
        collections={"WORKSHOP_PLAN_PARTICIPANTS_OPENING": {"participant": [
            {"serialNo": 1, "name": "One", "village": "Barpali", "district": "Bargarh",
             "state": "Odisha", "subjectLocation": {"lat": 21.20411, "lon": 83.60122}},
        ]}},
    ))
    block = _maps(document)[0]
    home = next(p for p in block.points if p.kind is MapPointKind.ARTISAN)
    assert (round(home.lat, 5), round(home.lon, 5)) == (21.20411, 83.60122)
    assert "drawn at its state capital" not in block.caption, \
        "a surveyed pin must never be described as an unresolvable place standing in on a capital"
    assert "Odisha" in block.highlight
    # ONE LABEL GRAMMAR PER FIGURE, pinned rather than left incidental. Every other pin on this map
    # is named by the atlas, which returns one token; this pin is named from the row, and the first
    # version of it carried the whole joined address ("Barpali, Bargarh"). A figure with two kinds
    # of label invites a reader to look for the distinction between them, and there is none.
    assert home.label == "Barpali", (
        f"the surveyed pin is labelled {home.label!r} where every geocoded pin beside it carries a "
        f"single place name"
    )


def test_a_zero_zero_subject_pin_falls_back_to_the_stated_address():
    """``0, 0`` is the Gulf of Guinea and is what a form that never obtained a fix writes. The
    artisan still has a stated village, and losing them off the map for a null pin would be the
    same regression ``_geo_point`` was written to prevent for the venue."""
    document = _build(_data(
        singletons={"WORKSHOP_SETUP": {"state": "Odisha", "district": "Bargarh"}},
        collections={"WORKSHOP_PLAN_PARTICIPANTS_OPENING": {"participant": [
            {"serialNo": 1, "name": "One", "village": "Barpali", "district": "Bargarh",
             "state": "Odisha", "subjectLocation": {"lat": 0.0, "lon": 0.0}},
        ]}},
    ))
    home = next(p for p in _maps(document)[0].points if p.kind is MapPointKind.ARTISAN)
    assert home.label == "Barpali"


def test_a_surveyed_pin_on_an_unplaceable_village_is_not_counted_as_approximate():
    """THE CAPTION HALF OF THE SURVEYED PIN, on a fixture where the sentence would actually print.

    ``test_a_pin_dropped_on_the_artisan_s_own_place_is_drawn_where_it_was_dropped`` also asserts
    the caption, but its village resolves precisely through the atlas, so that assertion would hold
    with the fix reverted too and proves nothing on its own. Here the village is one this build has
    never heard of: without the ``subjectLocation`` branch the row falls to the state seat, counts
    as approximate, and the report tells a reader that an artisan whose home was SURVEYED WITH A
    GPS was "drawn at its state capital" — the one sentence on the figure that a reader uses to
    decide how much of it to believe.
    """
    document = _build(_data(
        singletons={"WORKSHOP_SETUP": {"state": "Odisha", "district": "Bargarh"}},
        collections={"WORKSHOP_PLAN_PARTICIPANTS_OPENING": {"participant": [
            {"serialNo": 1, "name": "One", "village": "Kusumpur Tola", "state": "Odisha",
             "subjectLocation": {"lat": 21.20411, "lon": 83.60122}},
        ]}},
    ))
    block = _maps(document)[0]
    home = next(p for p in block.points if p.kind is MapPointKind.ARTISAN)
    assert (round(home.lat, 5), round(home.lon, 5)) == (21.20411, 83.60122)
    assert home.label == "Kusumpur Tola"
    assert "drawn at its state capital" not in block.caption, (
        "a home the researcher stood in and surveyed was reported to the reader as a village the "
        "atlas could not place, standing in on a capital city"
    )


def test_a_surveyed_pin_keeps_a_state_spelling_the_canonicaliser_does_not_know():
    """A PIN WHOSE STATE IS DROPPED IS A PIN ON AN UNTINTED REGION, with nothing saying why.

    Every other pin on this map carries ``canonical_state(found.state) or found.state`` — the
    canonical name where the closed list knows it, the record's own spelling where it does not.
    The surveyed pin was built with ``canonical_state(state) or ""``, so a state the list has never
    learned became the empty string, ``_render_map`` filtered it out of ``highlight``, and the
    figure drew a pin in a region it did not tint. Carried through, ``report_map._tint_states``
    still cannot seed the fill — but it returns the name in ``missed`` and the figure prints
    "Not tinted: …" beneath itself, which is a statement a reader can act on.

    "Kalinga" is not on the closed list under any alias (the renames it does carry are Orissa,
    Pondicherry and Uttaranchal), so it stands for the free-text spellings the older half of the
    corpus holds.
    """
    document = _build(_data(
        singletons={"WORKSHOP_SETUP": {"state": "Odisha", "district": "Bargarh"}},
        collections={"WORKSHOP_PLAN_PARTICIPANTS_OPENING": {"participant": [
            {"serialNo": 1, "name": "One", "village": "Kusumpur Tola", "state": "Kalinga",
             "subjectLocation": {"lat": 21.20411, "lon": 83.60122}},
        ]}},
    ))
    block = _maps(document)[0]
    assert "Kalinga" in block.highlight, (
        "the surveyed pin's state was dropped because the canonicaliser did not recognise the "
        "spelling, so the map drew a pin and tinted nothing under it"
    )


def test_artisans_from_one_place_fold_into_one_pin_that_counts_them():
    """Six weavers from Bagru resolve to one coordinate, and six pins stacked on that coordinate
    look like one pin — so a workshop of six read as a workshop of one."""
    document = _build(_data(
        singletons={"WORKSHOP_SETUP": {"state": "Rajasthan"}},
        collections={"WORKSHOP_PLAN_PARTICIPANTS_OPENING": {"participant": [
            {"serialNo": i, "name": f"A{i}", "artisanRef": f"a{i}"} for i in range(6)
        ]}},
        references={
            f"a{i}": ReferencedRecord(model="Artisan", label=f"A{i}", place="Bagru",
                                      district="Jaipur", state="Rajasthan")
            for i in range(6)
        },
    ))
    homes = [p for p in _maps(document)[0].points if p.kind is MapPointKind.ARTISAN]
    assert len(homes) == 1
    assert homes[0].count == 6
    assert "6 of 6" in _maps(document)[0].caption


def test_a_participant_who_stated_no_place_is_counted_in_the_caption_not_pinned():
    """Falling back to the workshop's own state for an artisan whose address was never recorded
    would stack thirty pins on the state capital and claim "artisans came from across Rajasthan"
    about a record that says nothing whatsoever. The gap goes in the caption instead."""
    document = _build(_data(
        singletons={"WORKSHOP_SETUP": {"state": "Rajasthan"}},
        collections={"WORKSHOP_PLAN_PARTICIPANTS_OPENING": {"participant": [
            {"serialNo": 1, "name": "Placed", "artisanRef": "a1"},
            {"serialNo": 2, "name": "Walked in on day two"},
        ]}},
        references={"a1": ReferencedRecord(model="Artisan", label="Placed", place="Bagru",
                                           district="Jaipur", state="Rajasthan")},
    ))
    block = _maps(document)[0]
    assert len([p for p in block.points if p.kind is MapPointKind.ARTISAN]) == 1
    assert "1 of 2" in block.caption
    assert "1 participant(s) recorded no address" in block.caption


def test_a_place_the_atlas_cannot_resolve_says_so_in_the_caption():
    """A village this build does not know is drawn at its state capital, hundreds of kilometres
    away. The pin is labelled with the STATE so it never claims to know more than it does, and
    the caption explains once, in words, why a pin may not sit where a reader expects.

    The atlas answers "only the state" for a string it half-recognises as readily as the state
    seat table does for one it does not recognise at all, and reading the first of those as a
    precise fix made the map candid about half its pins and silent about the other half.
    """
    document = _build(_data(
        singletons={"WORKSHOP_SETUP": {"state": "Rajasthan"}},
        collections={"WORKSHOP_PLAN_PARTICIPANTS_OPENING": {"participant": [
            {"serialNo": 1, "name": "One", "artisanRef": "a1"},
        ]}},
        references={"a1": ReferencedRecord(model="Artisan", label="One",
                                           place="Chhoti Dhani", district="Nagaur",
                                           state="Rajasthan")},
    ))
    block = _maps(document)[0]
    home = next(p for p in block.points if p.kind is MapPointKind.ARTISAN)
    assert home.label == "Rajasthan", "a state-precision pin must not wear the village's name"
    assert "drawn at its state capital" in block.caption


def test_the_map_tints_every_state_a_pin_landed_in_as_well_as_the_workshop_s_own():
    """A workshop whose artisans travelled from the next state over must tint both, or the map
    silently reports the workshop as a purely local event."""
    document = _build(_data(
        singletons={"WORKSHOP_SETUP": {"state": "Rajasthan"}},
        collections={"WORKSHOP_PLAN_PARTICIPANTS_OPENING": {"participant": [
            {"serialNo": 1, "name": "Visitor", "artisanRef": "a1"},
        ]}},
        references={"a1": ReferencedRecord(model="Artisan", label="Visitor", place="Bhuj",
                                           district="Kachchh", state="Gujarat")},
    ))
    assert {"Rajasthan", "Gujarat"} <= set(_maps(document)[0].highlight)


def test_the_venue_sentence_names_the_place_the_record_names():
    """A designer who typed "Bagru" and reads "Workshop venue: Rajasthan" concludes the report
    lost their answer. The pin's own label still says what the atlas resolved."""
    document = _build(_data(singletons={"WORKSHOP_SETUP": {
        "state": "Rajasthan", "district": "Jaipur", "village": "Bagru",
        "venue": "Cooperative hall",
    }}))
    assert "Cooperative hall, Bagru, Jaipur" in _maps(document)[0].caption


# --------------------------------------------------------------------------------------
# The infographics
# --------------------------------------------------------------------------------------


def test_a_section_with_no_data_emits_no_chart():
    """An empty workshop produces a cover, a contents page and not one figure.

    This is the rule the whole module is built around. A chart is read as a finding, and a
    finding derived from nothing is an invented one — on a document that becomes a sanctioned
    amount, that is not a cosmetic failure.
    """
    assert _charts(_build(_data())) == []


def test_a_chart_with_a_zero_total_is_never_drawn():
    """A follow-up round where nothing had been adopted yet.

    Three visits were made and three products are still under trial, so every category is
    honestly zero — and a line at zero across three intervals is a picture of a failure the
    record does not claim. It is also a division by zero waiting for the first renderer that
    forgets to check, which a donut of the same series would hit immediately.
    """
    document = _build(_data(collections={"POST_WORKSHOP_FOLLOWUP": {"followUp": [
        {"interval": "M3", "adoptionStatus": "TRIAL"},
        {"interval": "M6", "adoptionStatus": "TRIAL"},
        {"interval": "M12", "adoptionStatus": "NOT_ADOPTED"},
    ]}}))
    assert not [c for c in _charts(document) if "follow-up" in c.title.lower()]


def test_a_zero_total_block_is_refused_even_if_a_builder_returns_one():
    """The belt to the braces. Every ``_chart_*`` method already refuses to build a figure the
    record cannot fill; this guard catches the next one, written next year, that forgets to."""
    from app.services.report_model import ChartKind

    builder = ReportBuilder(_data(), template("DCH_STANDARD"), _resolver, meta=_meta())
    zeros = ChartBlock(kind=ChartKind.DONUT, title="A chart of nothing",
                       series=(("Sketches", 0.0), ("Prototypes", 0.0)))
    assert zeros.total == 0.0
    builder._chart_output_counts = lambda: zeros   # type: ignore[method-assign]
    assert builder._figure("OUTPUT_COUNTS") is None
    assert "OUTPUT_COUNTS" not in builder._drawn, \
        "a refused figure must stay undrawn, or a later section cannot try again"


def test_one_category_is_a_number_not_a_picture():
    """A single bar labelled "Sketches: 12" carries what the metric row already carries at the
    cost of a third of a page, and a one-slice donut is a filled circle."""
    document = _build(_data(collections={
        "SKETCH_DEVELOPMENT": {"sketch": [{"sketchNo": f"SK-{i}"} for i in range(9)]},
    }))
    assert not [c for c in _charts(document) if "prototypes and final" in c.title.lower()]


def test_the_whole_document_states_one_number_for_one_measure():
    """ONE AUTHORITY, and the stage says which: the override.

    Both halves used to be true at once. The chart and the front-page metric row each derived
    their counts from the rows, and stage 18's ``designsCountOverride`` printed RAW forty pages
    later as an ordinary key-value — so a designer who recorded 24 designs because only 18
    sketches were ever photographed into the record got a document reading "Sketches 10" at the
    front and "Number of designs (override) 24" in the middle, with the reason under the second
    one. An officer reading it cannot tell which figure to quote.

    The override wins wherever the count appears, the raw fields are HIDDEN so it is stated once,
    and the designer's reason is printed with it.
    """
    document = _build(_data(
        singletons={"WORKSHOP_OUTCOMES": {
            "designsCountOverride": 12,
            "countOverrideReason": "Only nine sketches were photographed into the record.",
        }},
        collections={
            "SKETCH_DEVELOPMENT": {"sketch": [{"sketchNo": f"SK-{i}"} for i in range(9)]},
            "PROTOTYPE_DEVELOPMENT": {"prototype": [{"prototypeCode": "PR-1"}]},
        },
    ))
    chart = _chart(document, "Designs, prototypes")
    assert dict(chart.series) == {"Sketches": 12.0, "Prototypes": 1.0}
    assert "photographed" in chart.caption, "the reason travels with the number it explains"

    from app.services.report_model import KeyValueBlock, MetricRowBlock

    metrics = next(b for b in document.blocks if isinstance(b, MetricRowBlock))
    assert {label: value for label, value, _unit in metrics.metrics}["Sketches"] == "12"

    # And the raw override is NOT also printed as a field of its own, anywhere.
    labels = [
        label
        for block in document.blocks if isinstance(block, KeyValueBlock)
        for label, _value in block.pairs
    ]
    assert not [x for x in labels if "override" in x.lower()]


def test_a_count_nobody_overrode_is_still_counted_from_the_records():
    """The default and the common case: derivation, so the report and the data cannot drift."""
    document = _build(_data(
        singletons={"WORKSHOP_OUTCOMES": {"achievements": "Done."}},
        collections={
            "SKETCH_DEVELOPMENT": {"sketch": [{"sketchNo": f"SK-{i}"} for i in range(9)]},
            "PROTOTYPE_DEVELOPMENT": {"prototype": [{"prototypeCode": "PR-1"}]},
        },
    ))
    chart = _chart(document, "Designs, prototypes")
    assert dict(chart.series) == {"Sketches": 9.0, "Prototypes": 1.0}
    assert "Counted from the records" in chart.caption


def test_a_cost_head_nobody_entered_is_absent_rather_than_zero():
    """"Transport ₹ 0" beside four real heads reads as "transport was free", when what the
    record says is that nobody entered it. On a sheet that becomes a sanctioned amount the
    difference between those two readings is the whole point of the sheet."""
    document = _build(_data(collections={"COSTING_MARKET_LINKAGE": {"costSheet": [
        {"materialCost": "400.00", "labourCost": "900.00"},
        {"materialCost": "600.00", "labourCost": "1200.00", "transportCost": "0.00"},
    ]}}))
    series = dict(_chart(document, "Cost by head").series)
    assert series == {"Material cost": 1000.0, "Labour cost": 2100.0}
    assert all(value > 0 for value in series.values())


def test_an_unparseable_cost_cell_does_not_poison_the_whole_breakdown():
    """``float("NaN")`` survives a plain ``float()`` call happily and then turns every sum it
    touches into NaN — one bad cell rendering an entire cost breakdown as bars of nothing."""
    document = _build(_data(collections={"COSTING_MARKET_LINKAGE": {"costSheet": [
        {"materialCost": "400.00", "labourCost": "900.00"},
        {"materialCost": "not a number", "labourCost": "NaN", "packagingCost": "150.00"},
    ]}}))
    series = dict(_chart(document, "Cost by head").series)
    assert series == {"Material cost": 400.0, "Labour cost": 900.0, "Packaging": 150.0}


def test_one_price_across_every_product_is_not_a_histogram():
    """A cluster that agreed one price for the whole range is a real state, and it draws as a
    single bar carrying nothing the price column does not already carry."""
    document = _build(_data(collections={"COSTING_MARKET_LINKAGE": {"costSheet": [
        {"expectedPrice": "1800.00"} for _ in range(5)
    ]}}))
    assert not [c for c in _charts(document) if "price band" in c.title.lower()]


def test_price_bands_are_numbers_a_person_would_choose():
    """A cost sheet binned at "₹ 0–1,383" is arithmetically correct and unreadable."""
    document = _build(_data(collections={"COSTING_MARKET_LINKAGE": {"costSheet": [
        {"expectedPrice": p} for p in ("400.00", "900.00", "1400.00", "2600.00", "4100.00")
    ]}}))
    labels = [label for label, _value in _chart(document, "price band").series]
    assert labels[0] == "0–999"
    assert all("," not in label or label.count(",") == 2 for label in labels)


def test_the_follow_up_line_runs_in_registry_order_not_alphabetical_order():
    """"M12" sorts before "M3", and a follow-up line that goes 3 → 12 → 6 months reads as a
    collapse and a recovery that never happened."""
    document = _build(_data(collections={"POST_WORKSHOP_FOLLOWUP": {"followUp": [
        {"interval": "M12", "adoptionStatus": "ADOPTED_IN_PRODUCTION"},
        {"interval": "M3", "adoptionStatus": "ADOPTED_IN_PRODUCTION"},
        {"interval": "M3", "adoptionStatus": "ADOPTED_ON_ORDER"},
        {"interval": "M6", "adoptionStatus": "ADOPTED_IN_PRODUCTION"},
    ]}}))
    series = _chart(document, "still in production").series
    assert [label for label, _value in series] == ["3 months", "6 months", "12 months"]
    assert [value for _label, value in series] == [2.0, 1.0, 1.0]


def test_an_interval_that_has_not_been_visited_yet_is_absent_not_zero():
    """A twelve-month column on a workshop that ran four months ago is not "zero adoption", it
    is a visit that has not happened."""
    document = _build(_data(collections={"POST_WORKSHOP_FOLLOWUP": {"followUp": [
        {"interval": "M3", "adoptionStatus": "ADOPTED_IN_PRODUCTION"},
        {"interval": "M6", "adoptionStatus": "ADOPTED_ON_ORDER"},
    ]}}))
    labels = [label for label, _value in _chart(document, "still in production").series]
    assert "12 months" not in labels


def test_a_figure_is_drawn_at_most_once_per_document():
    """DCH_STANDARD asks for the outcome figures at the front AND carries the outcomes stage
    twenty pages in. Printing the same picture twice makes a reader hunt for the difference."""
    document = _build(_data(collections={
        "SKETCH_DEVELOPMENT": {"sketch": [{"sketchNo": f"SK-{i}"} for i in range(4)]},
        "PROTOTYPE_DEVELOPMENT": {"prototype": [{"prototypeCode": "PR-1"}]},
        "PROTOTYPE_VALIDATION": {"prototypeValidation": [
            {"decision": "SELECTED"}, {"decision": "REVISE"},
        ]},
    }), "DCH_STANDARD")
    titles = [c.title for c in _charts(document)]
    assert len(titles) == len(set(titles)), f"a figure was printed twice: {titles}"


def test_a_chart_section_with_nothing_to_show_prints_no_heading_either():
    """A heading over an empty frame is how a generated report looks broken. The CHART section
    exists precisely for records that may have no figures in them."""
    from app.services.report_model import HeadingBlock, runs_text

    document = _build(_data(singletons={"WORKSHOP_SETUP": {"state": "Odisha"}}), "DCH_STANDARD")
    headings = [runs_text(b.runs) for b in document.blocks if isinstance(b, HeadingBlock)]
    assert not any("in figures" in h.lower() for h in headings)


# --------------------------------------------------------------------------------------
# Auto-placed photographs
# --------------------------------------------------------------------------------------


def _placed(document) -> list[tuple[str, str]]:
    """Every image the document places, as ``(media id, caption)``, in document order.

    Both block kinds, because :meth:`ReportBuilder._place_images` draws one photograph as an
    ``ImageBlock`` and several as a grid — a test that looked at only one of them would pass
    for a prototype with four photographs and miss the one with a single portrait.
    """
    out: list[tuple[str, str]] = []
    for block in document.blocks:
        if isinstance(block, ImageBlock):
            out.append((block.image.source, block.caption))
        elif isinstance(block, ImageGridBlock):
            out += [(image.source, caption) for image, caption in block.images]
    return out


def _in_place(data: WorkshopData):
    """Build under a template that has NO photographic annexure.

    DETAILED_TECHNICAL deliberately reprints every picture as a contact sheet at the back, so a
    count taken over its whole block list sees each photograph twice — which is correct
    behaviour and useless for testing placement. These tests are about the picture appearing
    BESIDE the thing it is a photograph of, so they read the body of the report only.
    """
    return _build(data, "DCH_STANDARD")


def test_a_ref_to_an_artisan_pulls_that_artisan_s_photograph():
    """THE GAP THIS CLOSES. A REF field stores an id and nothing else, and hydration copies only
    the display fields a designer chose — never a picture onto an entity that owns a gallery of
    its own. So the report described a participant, and the photograph of that participant sat
    in the media table one join away with nothing in the document pointing at it.
    """
    document = _in_place(_data(
        collections={"WORKSHOP_PLAN_PARTICIPANTS_OPENING": {"participant": [
            {"serialNo": 1, "name": "Bhikari Meher", "artisanRef": "a1"},
        ]}},
        references={"a1": ReferencedRecord(model="Artisan", label="Bhikari Meher",
                                           photo="media-portrait")},
    ))
    assert ("media-portrait", "Bhikari Meher") in _placed(document)


def test_the_photograph_is_captioned_with_the_record_s_name_not_the_field_s_label():
    """"Artisan" under a photograph is a category. "Bhikari Meher" is a caption."""
    document = _in_place(_data(
        collections={"EXISTING_PRODUCTS_BASELINE": {"existingProduct": [
            {"name": "Cotton saree", "artisanRef": "a1"},
        ]}},
        references={"a1": ReferencedRecord(model="Artisan", label="Sunita Bag",
                                           photo="media-portrait")},
    ))
    assert dict(_placed(document))["media-portrait"] == "Sunita Bag"


def test_a_ref_to_a_documented_product_pulls_the_catalogue_photograph():
    """``prototype.productRef`` seeds only a NAME, deliberately, and this docstring used to give
    the wrong reason for it — "hydration must never overwrite a gallery", which is not the rule.
    ``hydrate_entries`` seeds a gallery WHEN EMPTY and never overwrites one, and
    ``existingProduct.productPhotos`` is seeded from the product's own photograph on purpose. The
    prototype's gallery is left unseeded for the separate reason written at
    ``prototype.productRef``: a prototype is defined by how it DIFFERS from the product it derives
    from. Either way the picture has no copy on the row, so without the reference carrier the
    report described a prototype of a documented product with the product's photograph nowhere in
    it."""
    document = _in_place(_data(
        collections={"PROTOTYPE_DEVELOPMENT": {"prototype": [
            {"prototypeCode": "PR-01", "name": "Table runner", "productRef": "p1"},
        ]}},
        references={"p1": ReferencedRecord(model="ProductDocumentation",
                                           label="Pasapalli runner", photo="media-catalogue")},
    ))
    assert ("media-catalogue", "Pasapalli runner") in _placed(document)


def test_a_borrowed_photograph_is_captioned_with_the_name_the_row_froze():
    """THE ONE PLACE A LIVE RE-RESOLVED NAME REACHED PAPER.

    Every external REF in the registry is ``report_role=HIDDEN`` and none is any entity's
    ``label_field``, so a reference's ``label`` printed in exactly one position: the caption under
    the photograph borrowed from the record it points at. That label is
    ``REFERENCE_MODELS[model].label(row)`` evaluated against the record as it stands TODAY, while
    ``prototype.productName`` two lines up the same page is the frozen copy hydration wrote at save
    time. Rename the product record after the workshop closes and one page carries both answers:
    "Developed from: Sambalpuri Saree" above a photograph captioned "Sambalpuri Ikat Saree —
    revised 2027", with nothing to say which the workshop actually worked from.

    Generic, not per-entity: the caption follows ``reference_hydration_for``'s own ``"name"``
    mapping to whichever box on this row the name was copied into.
    """
    document = _in_place(_data(
        collections={"PROTOTYPE_DEVELOPMENT": {"prototype": [
            {"prototypeCode": "PR-01", "name": "Table runner", "productRef": "p1",
             "productName": "Sambalpuri Saree"},
        ]}},
        references={"p1": ReferencedRecord(model="ProductDocumentation",
                                           label="Sambalpuri Ikat Saree — revised 2027",
                                           photo="media-catalogue")},
    ))
    assert dict(_placed(document))["media-catalogue"] == "Sambalpuri Saree", (
        "the borrowed photograph was captioned with the product record's CURRENT name while the "
        "row beside it printed the name frozen at save time"
    )


def test_every_photographable_record_can_be_captioned_from_the_name_its_row_froze():
    """THE CENSUS THAT MAKES THE FROZEN-NAME CAPTION STAY CLOSED, AND IT FOUND ``Craft``.

    ``_reference_caption`` walked the hydration mapping looking for the literal source key
    ``"name"``. Four of the five reference models publish their display column under that key and
    the fifth does not: ``REFERENCE_MODELS["Craft"].data`` emits ``{"craftName": r.name, …}`` while
    its ``label`` is ``r.name`` — the same column, a different key, because stage 1's cover asks
    for "Craft name". ``Craft`` also declares ``media_field="craftId"``, so a craft photograph
    really does reach ``_images``' second pass, and it was captioned from the LIVE record while
    ``workshopSetup.craftName`` — a COVER_FIELD — printed the copy frozen at save time. Rename a
    craft after the workshop closes and the COVER PAGE and the picture beneath it carry two names
    for one craft.

    DERIVED, NOT ENUMERATED, so the next model whose ``data`` lambda renames its key fails here
    instead of on a ministry's cover page: the name key is found by asking each model which of its
    published keys carries the same value its ``label`` reads, over a probe row that answers every
    column with its own name. Nothing in this test consults
    ``report_builder._REFERENCE_NAME_SOURCES``, which is the table under test.
    """
    import logging

    from app.services import design_workshops as dw
    from app.services.stage_schema import reference_hydration_for

    class _Echo:
        """A record whose every column answers with its own name, so ``label`` and ``data`` can be
        compared BY VALUE without a database. ``__getattr__`` rather than fixed attributes: a
        lambda that starts reading a new column must not make the probe raise."""

        def __getattr__(self, name: str) -> str:
            return f"<{name}>"

    builder = ReportBuilder(_data(), template("DETAILED_TECHNICAL"), _resolver, meta=_meta())
    checked = 0
    for model, spec in dw.REFERENCE_MODELS.items():
        if not spec.media_field:
            # No photograph can be borrowed from this model at all — ``Process`` says at length why
            # it has none — so there is no caption to get wrong.
            continue
        probe = _Echo()
        label = spec.label(probe)
        # THE PROBE TRIPS THE CARRY WARNINGS, WHICH IS NOISE AND NOT A FINDING. Four of these
        # lambdas run an ENUM token through ``_translated``, which logs an ERROR naming a token no
        # translation table carries — true of "<productType>" and of nothing a real record holds.
        # Left to print, this test emits five ERROR lines on every green run, which is how a suite
        # teaches its readers that ERROR lines mean nothing.
        logging.disable(logging.ERROR)
        try:
            published = spec.data(probe, None)
        finally:
            logging.disable(logging.NOTSET)
        name_keys = {key for key, value in published.items() if value == label}
        assert name_keys, (
            f"REFERENCE_MODELS[{model!r}].data publishes nothing equal to its own label, so no "
            f"hydration mapping can carry the record's name onto a row and every borrowed "
            f"photograph of one is captioned from the live record"
        )
        for stage in stages():
            for entity in stage.entities:
                for field in entity.fields:
                    if field.type is not FieldType.REF or field.ref_model != model:
                        continue
                    mapping = reference_hydration_for(entity.key, field.key)
                    if not mapping:
                        continue
                    targets = [mapping[key] for key in sorted(name_keys) if key in mapping]
                    assert targets, (
                        f"{entity.key}.{field.key} hydrates from {model} but copies none of "
                        f"{sorted(name_keys)} — the record's own name — onto the row, so its "
                        f"borrowed photograph can only be captioned from the live record"
                    )
                    checked += 1
                    frozen = "The name this row froze"
                    caption = builder._reference_caption(
                        entity, field, {targets[0]: frozen},
                        ReferencedRecord(model=model, label="RENAMED AFTER SUBMISSION",
                                         photo="media-1"),
                    )
                    assert caption == frozen, (
                        f"the photograph borrowed through {entity.key}.{field.key} is captioned "
                        f"{caption!r} — the {model} record's name AS IT STANDS TODAY — while the "
                        f"row beside it prints {frozen!r}"
                    )
    assert checked >= 6, (
        f"only {checked} REF field(s) reached the caption assertion; this census was written "
        f"against six and a shrinking count means a photographable model stopped being covered"
    )


def test_a_borrowed_photograph_still_falls_back_to_the_record_s_own_name():
    """Where the mapping seeds no name — ``existingProduct.artisanRef`` writes ``artisanName`` and
    a row saved before that mapping existed carries none — the reference's label is still the right
    caption, for the reason the replaced line gave: the field's label is the RELATIONSHIP and not
    the subject, so "Artisan" under a photograph is a category where "Sunita Bag" is a caption."""
    document = _in_place(_data(
        collections={"EXISTING_PRODUCTS_BASELINE": {"existingProduct": [
            {"name": "Cotton saree", "artisanRef": "a1"},
        ]}},
        references={"a1": ReferencedRecord(model="Artisan", label="Sunita Bag",
                                           photo="media-portrait")},
    ))
    assert dict(_placed(document))["media-portrait"] == "Sunita Bag"


def test_the_designer_s_own_photographs_come_before_the_borrowed_one():
    """A prototype whose maker shot progress photographs must not lead with a catalogue picture
    of the product it was based on. The report is about the workshop; the borrowed image is
    context, and the registry's field ORDER must not be what decides which leads — ``productRef``
    happens to be declared five fields above ``prototypePhotos``."""
    document = _in_place(_data(
        collections={"PROTOTYPE_DEVELOPMENT": {"prototype": [
            {"prototypeCode": "PR-01", "name": "Table runner", "productRef": "p1",
             "prototypePhotos": ["own-1", "own-2"],
             "prototypePhotosCaption": "Tying the warp"},
        ]}},
        references={"p1": ReferencedRecord(model="ProductDocumentation", label="Pasapalli",
                                           photo="media-catalogue")},
    ))
    assert [media_id for media_id, _caption in _placed(document)] == \
        ["own-1", "own-2", "media-catalogue"]


def test_a_hydrated_photograph_is_not_printed_twice():
    """``participant.artisanRef`` DOES seed ``participant.photo`` at save time, so the row's own
    media field and the reference resolve to the very same media row. Deduplicating by media id
    rather than by field is what keeps one portrait from appearing twice under one name."""
    document = _in_place(_data(
        collections={"WORKSHOP_PLAN_PARTICIPANTS_OPENING": {"participant": [
            {"serialNo": 1, "name": "Bhikari Meher", "artisanRef": "a1", "photo": "same-media"},
        ]}},
        references={"a1": ReferencedRecord(model="Artisan", label="Bhikari Meher",
                                           photo="same-media")},
    ))
    placed = [media_id for media_id, _caption in _placed(document)]
    assert placed.count("same-media") == 1


def test_a_reference_with_no_photograph_places_nothing():
    """A roster row picked from a list for an artisan nobody has photographed is ordinary."""
    document = _in_place(_data(
        collections={"WORKSHOP_PLAN_PARTICIPANTS_OPENING": {"participant": [
            {"serialNo": 1, "name": "Bhikari Meher", "artisanRef": "a1"},
        ]}},
        references={"a1": ReferencedRecord(model="Artisan", label="Bhikari Meher")},
    ))
    assert _placed(document) == []


def test_a_ref_whose_record_never_loaded_is_not_an_error():
    """``WorkshopData.references`` empty is a supported state — it is what the on-device builder
    hands over, and what a reference load that failed leaves behind. The report loses a pin and
    a picture and prints."""
    document = _in_place(_data(collections={"WORKSHOP_PLAN_PARTICIPANTS_OPENING": {"participant": [
        {"serialNo": 1, "name": "Bhikari Meher", "artisanRef": "a1"},
    ]}}))
    assert _placed(document) == []
    assert document.blocks


def test_a_referenced_photograph_the_resolver_cannot_find_is_skipped_not_fatal():
    """A photo that failed to sync is a gap the report survives, not an error."""
    document, _warnings = build_report(
        _data(
            collections={"WORKSHOP_PLAN_PARTICIPANTS_OPENING": {"participant": [
                {"serialNo": 1, "name": "Bhikari Meher", "artisanRef": "a1"},
            ]}},
            references={"a1": ReferencedRecord(model="Artisan", label="Bhikari Meher",
                                               photo="gone")},
        ),
        "DETAILED_TECHNICAL", lambda _media_id: None, meta=_meta(),
    )
    assert document.images == ()


def test_a_template_that_excludes_photographs_places_no_referenced_one_either():
    """PHOTO_CATALOGUE prints the makers with ``include_photos=False``. A borrowed picture must
    obey the same instruction the row's own pictures obey, or the exclusion is a half-measure."""
    document = _build(_data(
        collections={"WORKSHOP_PLAN_PARTICIPANTS_OPENING": {"participant": [
            {"serialNo": 1, "name": "Bhikari Meher", "artisanRef": "a1"},
        ]}},
        references={"a1": ReferencedRecord(model="Artisan", label="Bhikari Meher",
                                           photo="media-portrait")},
    ), "PHOTO_CATALOGUE")
    assert "media-portrait" not in [media_id for media_id, _c in _placed(document)]


def test_the_media_annexure_gathers_the_referenced_photographs_too():
    """DETAILED_TECHNICAL is the archival copy and its contact sheet is meant to be exhaustive.
    A picture the body of the report placed but the annexure missed would leave a reader who
    counted the annexure believing the record holds fewer photographs than it does."""
    data = _data(
        collections={"WORKSHOP_PLAN_PARTICIPANTS_OPENING": {"participant": [
            {"serialNo": 1, "name": "Bhikari Meher", "artisanRef": "a1"},
        ]}},
        references={"a1": ReferencedRecord(model="Artisan", label="Bhikari Meher",
                                           photo="media-portrait")},
    )
    archival = [media_id for media_id, _c in _placed(_build(data, "DETAILED_TECHNICAL"))]
    assert archival.count("media-portrait") == 2, \
        "once beside the roster row and once in the annexure"


# --------------------------------------------------------------------------------------
# Rich text
# --------------------------------------------------------------------------------------
#
# No field in the registry is RICH_TEXT yet — the type, the editor, the normaliser and both
# renderers landed before the first field was promoted to it. That is exactly why these tests
# build their own entity rather than waiting for one: the day somebody changes a LONG_TEXT
# declaration to RICH_TEXT, the report path either already carries the marks or it silently
# prints the document's own JSON into a ministry submission, and finding out then is finding out
# too late.


def _rich(*spans: tuple[str, tuple[str, ...]], kind: str = "PARAGRAPH") -> dict:
    return {"blocks": [{"kind": kind,
                        "spans": [{"text": text, "marks": list(marks)} for text, marks in spans]}]}


def _rich_entity():
    from app.services.stage_schema import Cardinality, EntitySpec, FieldSpec, FieldType, ReportRole

    return EntitySpec(
        key="richDemo", name="DwRichDemo", cardinality=Cardinality.SINGLETON,
        title="Rich demo",
        fields=(
            FieldSpec(key="prose", label="Findings", type=FieldType.RICH_TEXT,
                      report_role=ReportRole.NARRATIVE),
            FieldSpec(key="note", label="Note", type=FieldType.RICH_TEXT,
                      report_role=ReportRole.KEY_VALUE),
        ),
    )


def _rendered(row: dict):
    builder = ReportBuilder(_data(), template("DETAILED_TECHNICAL"), _resolver, meta=_meta())
    builder._render_narrative(_rich_entity(), row, 1)
    return builder.doc.build()


def test_rich_text_reaches_the_document_with_its_marks():
    """THE ONLY PATH THAT KEEPS THE FORMATTING. A designer who bolded three product names and
    numbered five recommendations wrote structure, not decoration; flattening it here would make
    the rich-text editor a more expensive textarea."""
    from app.services.report_model import ParagraphBlock

    document = _rendered({"prose": _rich(
        ("The cluster produces ", ()),
        ("Pasapalli", ("BOLD",)),
        (" and ", ()),
        ("Bandha", ("ITALIC", "UNDERLINE")),
        (" ranges.", ()),
    )})
    runs = [run for block in document.blocks if isinstance(block, ParagraphBlock)
            for run in block.runs]
    marked = {run.text: (run.bold, run.italic, run.underline) for run in runs}
    assert marked["Pasapalli"] == (True, False, False)
    assert marked["Bandha"] == (False, True, True)


def test_a_rich_text_list_becomes_one_list_block_not_five():
    """Emitting one block per item restarts the numbering at every line — a five-point
    recommendation list printed as "1. 1. 1. 1. 1."."""
    from app.services.report_model import BulletListBlock

    value = {"blocks": [{"kind": "ORDERED_ITEM", "spans": [{"text": f"Point {i}"}]}
                        for i in range(5)]}
    document = _rendered({"prose": value})
    lists = [b for b in document.blocks if isinstance(b, BulletListBlock)]
    assert len(lists) == 1
    assert lists[0].ordered is True
    assert len(lists[0].items) == 5


def test_rich_text_in_a_key_value_cell_keeps_its_marks():
    """A key-value cell holds runs and cannot hold a block, which is the same constraint a table
    cell is under and gets the same answer: ``rich_text.plain_runs``. The alternative was
    ``runs_of(str(the stored dict))``, which printed the document's own JSON into the cell."""
    from app.services.report_model import KeyValueBlock

    document = _rendered({"note": _rich(("Delivered ", ()), ("late", ("BOLD",)))})
    pairs = [pair for block in document.blocks if isinstance(block, KeyValueBlock)
             for pair in block.pairs]
    label, runs = next(pair for pair in pairs if pair[0] == "Note")
    assert label == "Note"
    assert [(run.text, run.bold) for run in runs] == [("Delivered ", False), ("late", True)]


def test_a_rich_text_value_never_prints_as_its_own_json():
    """The failure that made this whole path necessary: a RICH_TEXT dict fell through to
    ``clean_text``, which stringifies whatever it is given, so the report printed
    ``{'blocks': [{'kind': 'PARAGRAPH', ...}]}`` into a ministry submission — and every emptiness
    check read that JSON-shaped string as a filled field, so nothing reported a problem."""
    from app.services.report_model import runs_text

    document = _rendered({
        "prose": _rich(("A finding.", ())),
        "note": _rich(("A note.", ())),
    })
    everything = " ".join(
        runs_text(getattr(block, "runs", ()) or ())
        for block in document.blocks
    ) + " ".join(
        runs_text(value)
        for block in document.blocks
        for _label, value in getattr(block, "pairs", ()) or ()
    )
    assert "'blocks'" not in everything and "PARAGRAPH" not in everything
    assert "A finding." in everything and "A note." in everything


def test_an_empty_rich_value_prints_nothing_at_all():
    """An editor that has been opened and closed stores an empty document, not None."""
    document = _rendered({"prose": {"blocks": [{"kind": "PARAGRAPH", "spans": []}]}})
    assert document.blocks == ()


def test_a_plain_string_under_a_promoted_field_still_prints():
    """Promoting LONG_TEXT to RICH_TEXT must not blank the prose already stored under it."""
    from app.services.report_model import runs_text

    document = _rendered({"prose": "Written before the field was promoted."})
    text = " ".join(runs_text(getattr(b, "runs", ()) or ()) for b in document.blocks)
    assert "Written before the field was promoted." in text


# --------------------------------------------------------------------------------------
# Finding the records a report REFERENCES, without a database
# --------------------------------------------------------------------------------------


def test_reference_ids_finds_only_the_external_models():
    """A ``Dw…`` ref_model points at another entry OF THIS SAME WORKSHOP, already loaded. Looking
    one up as though it were an artisan would query a Prisma delegate that does not exist."""
    from app.services.design_workshops import reference_ids

    class _Row:
        def __init__(self, entity_key, data):
            self.entityKey, self.data = entity_key, data

    found = reference_ids([
        _Row("participant", {"artisanRef": "artisan-1"}),
        _Row("participant", {"artisanRef": "artisan-2"}),
        _Row("participant", {"artisanRef": "artisan-1"}),          # the same artisan twice
        _Row("participant", {"name": "typed in by hand"}),         # no reference at all
        _Row("prototype", {"productRef": "product-1", "sketchRef": "dw-entry-1"}),
        _Row("costSheet", {"productRef": "dw-final-1"}),           # a DwFinalProduct, not external
    ])
    assert found == {"Artisan": {"artisan-1", "artisan-2"},
                     "ProductDocumentation": {"product-1"}}


def test_reference_ids_of_an_empty_record_is_empty():
    from app.services.design_workshops import reference_ids

    assert reference_ids([]) == {}


def test_a_referenced_record_states_its_village_before_its_free_text_place():
    """``Location.village`` is the stated address a researcher typed into the closed hierarchy;
    ``Artisan.place`` is the free text that was all the corpus had before those columns existed.
    Preferring the free text would ignore the better answer on every modern record."""
    from app.services.design_workshops import _reference_place

    class _Location:
        village, district, state = "Bagru", "Jaipur", "Rajasthan"

    class _Artisan:
        location, place = _Location(), "somewhere near Jaipur"

    assert _reference_place(_Artisan()) == ("Bagru", "Jaipur", "Rajasthan")


def test_a_referenced_record_with_no_location_row_still_offers_its_place():
    """Half the corpus predates the stated-address columns, and a model whose picker query does
    not include the relation — a documented product — arrives with no location attribute at all."""
    from app.services.design_workshops import _reference_place

    class _Product:
        place = "Barpali"

    assert _reference_place(_Product()) == ("Barpali", "", "")


# --------------------------------------------------------------------------------------
# The two halves of one document must agree
# --------------------------------------------------------------------------------------


def test_a_required_reference_to_a_deleted_row_is_not_counted_as_recorded():
    """THE DOCUMENT THAT DISAGREED WITH ITSELF.

    The completeness annexure read "13. Prototype Development | 144/144 | 100% | Complete" and
    no warning was emitted for stage 13 — while eighteen pages earlier the SAME report printed
    "Prototype | Not recorded." in all 18 prototypeStageLog tables and all 18 materialUsage
    tables. Thirty-six occurrences, identical in the .docx and the PDF. The renderer blanks an
    opaque id whose row was deleted; the scorer only checked that the string was non-empty.

    ``prototypeRef`` below names a row that is not in the record, exactly as a deleted parent
    does — and "Stage logs: Prototype" is the label that must appear in the annexure's
    Outstanding column and nowhere appeared before.
    """
    from app.services.report_model import TableBlock, runs_text

    data = _data(collections={"PROTOTYPE_DEVELOPMENT": {
        "prototype": [],
        "prototypeStageLog": [
            {"prototypeRef": "cmsdeletedrow0001", "stageName": "Dyeing",
             "logDate": "2026-01-20", "notes": "Second dip."},
        ],
    }})
    document, warnings = build_report(data, "DETAILED_TECHNICAL", _resolver, meta=_meta())

    completeness = next(
        (b for b in document.blocks
         if isinstance(b, TableBlock) and any(c.header == "Stage" for c in b.columns)),
        None,
    )
    assert completeness is not None, "the completeness annexure did not render"
    row = next(r for r in completeness.rows if "Prototype Development" in runs_text(r[0]))
    assert "Prototype" in runs_text(row[3]), (
        "the annexure counted a reference to a deleted row as recorded, while the body of the "
        f"same document prints 'Not recorded.' for it; outstanding was {runs_text(row[3])!r}"
    )
    assert any("Prototype Development" in w for w in warnings)


def test_a_reference_that_still_resolves_is_still_counted():
    """The other half, asserted on the scorer so the claim is exact: an id that resolves counts
    as filled, and only an id that does not stops counting."""
    from app.services.stage_schema import stage, stage_completeness

    spec = stage("PROTOTYPE_DEVELOPMENT")
    rows = {"prototypeStageLog": [
        {"prototypeRef": "pr-1", "stageName": "Dyeing", "logDate": "2026-01-20",
         "notes": "Second dip."},
    ]}
    resolving = stage_completeness(spec, {}, rows, ref_resolves=lambda _v: True)
    dangling = stage_completeness(spec, {}, rows, ref_resolves=lambda _v: False)
    unchecked = stage_completeness(spec, {}, rows)

    assert resolving.required_filled == unchecked.required_filled
    assert dangling.required_filled == unchecked.required_filled - 1
    assert "Stage logs: Prototype" in dangling.missing
    assert "Stage logs: Prototype" not in resolving.missing


# ------------------------------------------------------------------------------------------
# Opaque ids must not reach the page, whatever the field's declared type
# ------------------------------------------------------------------------------------------


def test_a_text_field_holding_a_bare_record_id_prints_nothing_rather_than_the_id():
    """The REF guard's argument, applied to the field that actually reaches a reader.

    `_looks_like_an_id` was written for REF and stopped there. Stage 21's
    `mediaQualityFlag.mediaId` is declared TEXT — there is no `Media` ref_model to point at — and
    it is that entity's `label_field`, so a designer flagging a blurred photograph grew a table
    whose File column read `cmsjb6qaq01ar4otfh1p0hm1a`. A bare cuid in a ministry's table is worse
    than a visible gap: the gap is legible as missing, the cuid looks like an answer.
    """
    from app.services.report_builder import _looks_like_an_id

    assert _looks_like_an_id("cmsjb6qaq01ar4otfh1p0hm1a") is True
    assert _looks_like_an_id("SK-01") is False
    assert _looks_like_an_id("Sambalpuri stole") is False
    # Free text that merely CONTAINS an id keeps every character — it is a designer's own words.
    assert _looks_like_an_id("duplicate of cmsjb6qaq01ar4otfh1p0hm1a") is False


def test_no_template_prints_a_bare_record_id_through_a_text_field():
    """The guard above is DEFENCE IN DEPTH, and this test says honestly why.

    `mediaQualityFlag.mediaId` is the only TEXT field in the registry whose name implies an id and
    which carries a printing role — and it is that entity's `label_field`, so if its stage were ever
    printed the table's File column and every card heading would read `cmsjb6qaq01ar4otfh1p0hm1a`.
    Today it is not printed: NO template includes `DATA_QUALITY_ARCHIVE`, so the leak is latent
    rather than live, and claiming otherwise would be claiming a fix for a bug nobody could see.

    Both halves are asserted, so the day somebody adds stage 21 to a template — a reasonable thing
    to want — this test fails and points at the guard that already handles it, instead of the change
    silently shipping cuids into a document submitted to a ministry.
    """
    from app.services.stage_schema import STAGES, FieldType, ReportRole

    printed = {section.stage_key for t in TEMPLATES for section in t.sections if section.stage_key}
    suspects = [
        (stage.key, entity.key, spec.key)
        for stage in STAGES
        for entity in stage.entities
        for spec in entity.fields
        if spec.type is FieldType.TEXT
        and spec.report_role is not ReportRole.HIDDEN
        and (spec.key.lower().endswith("id") or spec.key.lower().endswith("ref"))
    ]
    assert suspects == [("DATA_QUALITY_ARCHIVE", "mediaQualityFlag", "mediaId")], (
        f"a new TEXT field holding a record id appeared: {suspects}. It must either be a REF, or "
        f"be proven not to print a bare cuid — `_value` suppresses one, but only for TEXT."
    )
    assert "DATA_QUALITY_ARCHIVE" not in printed, (
        "stage 21 is now printed by a template. That is fine — but check the Media quality flags "
        "table, whose label field is a bare media id, actually reads legibly in the .docx and PDF."
    )

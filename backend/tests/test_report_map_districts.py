"""The report map places artisans anywhere in India, not only in the curated craft towns.

THE FAILURE THIS REPLACES WAS SILENT AND CONFIDENT. `place_atlas` is a hand-checked table of a few
dozen craft towns. Everywhere it does not name — which is most of the country — `_geocode` fell
through to the state capital, so every artisan of a Bargarh cluster folded onto one pin at
Bhubaneswar and the figure asserted they all came from the capital, 300 km away. The map rendered
cleanly and nothing warned, which is exactly why it survived so long.

Curating another state would have moved the same failure to the next one. `address` already names
all 795 districts of India and `geography.DistrictAnchors` already positions them from the pins
this repository holds; the map simply never received them.
"""

import app.services.stage_definitions  # noqa: F401  - installs the registry
from app.services.address import DISTRICTS_BY_STATE
from app.services.geography import district_key
from app.services.report_builder import _geocode

#: Districts in states the curated atlas covers NOT AT ALL. If this test is ever made to pass by
#: adding these to `place_atlas`, it has been defeated — pick another state.
UNCURATED = (
    ("Nagaland", "Mokokchung"),
    ("Manipur", "Thoubal"),
    ("Kerala", "Kannur"),
    ("Tamil Nadu", "Kanchipuram"),
    ("Bihar", "Madhubani"),
    ("Assam", "Sivasagar"),
)


def _anchors(pairs):
    return {district_key(s, d): (20.0 + i, 80.0 + i) for i, (s, d) in enumerate(pairs)}


def test_the_register_names_every_district_in_the_country():
    assert sum(len(v) for v in DISTRICTS_BY_STATE.values()) == 795
    for state, district in UNCURATED:
        assert district in DISTRICTS_BY_STATE[state], f"{district} is not a {state} district"


def test_an_uncurated_district_folds_to_the_state_without_anchors():
    """The old behaviour, pinned so the improvement below is measured against something real."""
    for state, district in UNCURATED:
        located = _geocode(district, state)
        assert located is not None
        assert located.precise is False, f"{district} should not be precise without an anchor"
        assert located.label == state, f"{district} stood in at the state, as {located.label}"


def test_an_uncurated_district_resolves_precisely_with_anchors():
    """The whole point: any of the 795, in any state, with no curation."""
    points = _anchors(UNCURATED)
    for state, district in UNCURATED:
        located = _geocode(district, state, points)
        assert located is not None, f"{state}/{district} did not resolve"
        assert located.precise is True, f"{state}/{district} resolved but not precisely"
        assert located.label == district
        assert (located.lat, located.lon) == points[district_key(state, district)]


def test_a_town_still_beats_its_district():
    """A curated town is finer than a district anchor and must keep winning."""
    points = _anchors((("Rajasthan", "Jaipur"),))
    located = _geocode("Bagru", "Rajasthan", points)
    assert located is not None and located.label == "Bagru"


def test_the_district_is_found_inside_a_longer_address():
    """Records state "village, district", not a bare district name."""
    points = _anchors((("Odisha", "Bargarh"),))
    located = _geocode("Barpali village, Bargarh", "Odisha", points)
    assert located is not None and located.precise
    assert located.label in {"Barpali", "Bargarh"}


def test_an_unplaceable_district_still_falls_back_rather_than_vanishing():
    """A district with no anchor — nothing pinned in it yet — must not lose its pin entirely."""
    located = _geocode("Mokokchung", "Nagaland", {})
    assert located is not None
    assert located.precise is False

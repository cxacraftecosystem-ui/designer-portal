import app.services.stage_definitions  # noqa: F401  - installs the registry
from app.services.address import DISTRICTS_BY_STATE
from app.services.geography import district_key
from app.services.report_builder import _geocode

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
    for state, district in UNCURATED:
        located = _geocode(district, state)
        assert located is not None
        assert located.precise is False, f"{district} should not be precise without an anchor"
        assert located.label == state, f"{district} stood in at the state, as {located.label}"


def test_an_uncurated_district_resolves_precisely_with_anchors():
    points = _anchors(UNCURATED)
    for state, district in UNCURATED:
        located = _geocode(district, state, points)
        assert located is not None, f"{state}/{district} did not resolve"
        assert located.precise is True, f"{state}/{district} resolved but not precisely"
        assert located.label == district
        assert (located.lat, located.lon) == points[district_key(state, district)]


def test_a_town_still_beats_its_district():
    points = _anchors((("Rajasthan", "Jaipur"),))
    located = _geocode("Bagru", "Rajasthan", points)
    assert located is not None and located.label == "Bagru"


def test_the_district_is_found_inside_a_longer_address():
    points = _anchors((("Odisha", "Bargarh"),))
    located = _geocode("Barpali village, Bargarh", "Odisha", points)
    assert located is not None and located.precise
    assert located.label in {"Barpali", "Bargarh"}


def test_an_unplaceable_district_still_falls_back_rather_than_vanishing():
    located = _geocode("Mokokchung", "Nagaland", {})
    assert located is not None
    assert located.precise is False

"""What the accent derivation promises, pinned.

Three of these tests exist because of a specific way a coloured report goes wrong in an office:
a table header nobody can read, a heading that is not on the page at all, and an export that
refuses to happen because a colour string was malformed. The fourth — the golden table at the
bottom — exists because the derivation runs in three languages, and the Kotlin and TypeScript
ports are checked against exactly these values.
"""

from itertools import pairwise

import pytest

from app.services.report_templates import DCH_THEME
from app.services.report_theme import (
    ACCENT_PRESET_ENUM,
    ACCENT_PRESETS,
    CUSTOM_PRESET,
    DEFAULT_ACCENT,
    PAPER,
    contrast_ratio,
    normalise_hex,
    relative_luminance,
    resolve_accent,
    theme_from_accent,
)

# --------------------------------------------------------------------------------------
# Reading a hex
# --------------------------------------------------------------------------------------


@pytest.mark.parametrize(
    ("given", "expected"),
    [
        ("#1F3864", "1F3864"),
        ("1f3864", "1F3864"),
        ("  #1f3864  ", "1F3864"),
        ("#1F8", "11FF88"),          # CSS shorthand, doubled — not zero-padded
        ("", None),
        ("   ", None),
        ("blue", None),
        ("#12345", None),
        ("#1234567", None),
        ("rgb(1,2,3)", None),
        (None, None),
        (7, None),
        (0x1F3864, None),            # an int that LOOKS like the colour is still not a string
    ],
)
def test_normalise_hex(given, expected):
    assert normalise_hex(given) == expected


# --------------------------------------------------------------------------------------
# The fallback — never an exception, never a failed export
# --------------------------------------------------------------------------------------


@pytest.mark.parametrize("rubbish", [None, "", "   ", "blue", "#12345", 7, [], {"hex": "#fff"}])
def test_malformed_accent_falls_back_to_the_dch_indigo(rubbish):
    """A designer must never be unable to generate a report because a colour string was wrong.

    The failure this prevents is not hypothetical: ``themeAccent`` is free text on a stage entry
    that six years of clients will write to, and the report route runs inside a thread the
    designer is watching a spinner for. An exception here is a 500 on the download button.
    """
    assert theme_from_accent(rubbish).accent == DEFAULT_ACCENT


def test_the_fallback_palette_is_the_one_the_report_has_always_used():
    """Deriving from the DCH accent reproduces the template's hand-picked palette.

    Those colours were chosen by somebody looking at printed reports, so matching them is the
    closest thing the derivation has to a ground truth — and it means turning this feature on
    does not visibly restyle a report that asked for the colour it already had. The rule and the
    zebra fill land exactly; the soft accent and the muted text land within one hex digit, which
    is below the threshold at which a laser printer or a reader can tell.
    """
    derived = theme_from_accent(DEFAULT_ACCENT)
    assert derived.rule == DCH_THEME.rule
    assert derived.zebra_fill == DCH_THEME.zebra_fill
    assert derived.table_header_fill == DCH_THEME.table_header_fill
    for ours, theirs in ((derived.accent_soft, DCH_THEME.accent_soft),
                         (derived.muted, DCH_THEME.muted)):
        assert contrast_ratio(ours, theirs) < 1.02


def test_a_base_theme_keeps_its_fonts_and_its_type_size():
    """The photo catalogue sets 10pt; recolouring it must not silently reset it to 10.5.

    Every colour comes from the accent and everything that is not a colour comes from the base,
    because the two are separate decisions: a template's typography is the template's, and a
    designer choosing maroon has not asked for a different point size.
    """
    from app.services.report_templates import CATALOGUE_THEME

    derived = theme_from_accent("802F42", base=CATALOGUE_THEME)
    assert derived.base_size_pt == CATALOGUE_THEME.base_size_pt
    assert derived.heading_font == CATALOGUE_THEME.heading_font
    assert derived.accent == "802F42"


# --------------------------------------------------------------------------------------
# The table header — the choice that must be measured
# --------------------------------------------------------------------------------------


def test_a_dark_accent_gets_white_header_text():
    assert theme_from_accent("1F3864").table_header_text == PAPER


@pytest.mark.parametrize("pale", ["FFE680", "F2C744", "E8F0A0", "FFFFFF", "D9F0FF"])
def test_a_pale_accent_gets_dark_header_text(pale):
    """White 9pt bold on pale yellow is an unreadable header row, on screen and on a laser.

    A designer who picks pale yellow must not be able to produce one, and the guard cannot be a
    guess from the hue: this is decided by relative luminance, which is why an olive and a teal
    that look equally "medium" resolve differently.
    """
    theme = theme_from_accent(pale)
    assert theme.table_header_text == theme.ink
    assert theme.table_header_text != PAPER


@pytest.mark.parametrize("accent", [preset.hex for preset in ACCENT_PRESETS])
def test_every_preset_is_legible_where_it_is_used(accent):
    """The three places a preset lands on paper, each with the bar that applies to it.

    The header row is small bold text reversed out of a fill, so 4.5:1. The accent and the soft
    accent are heading text on white — large, so 3:1 — and the accent's own bar is set higher
    because an H1 that only just clears the minimum is a section title an officer squints at.
    """
    theme = theme_from_accent(accent)
    assert contrast_ratio(theme.table_header_fill, theme.table_header_text) >= 4.5
    assert contrast_ratio(theme.accent, PAPER) >= 4.5
    assert contrast_ratio(theme.accent_soft, PAPER) >= 3.0
    assert contrast_ratio(theme.ink, PAPER) >= 12.0
    assert contrast_ratio(theme.muted, PAPER) >= 4.0
    # The zebra stripe is a background under body text, not a colour in its own right: it has to
    # be visible against the paper and must not eat into the contrast the ink has on it.
    assert contrast_ratio(theme.zebra_fill, PAPER) < 1.12
    assert contrast_ratio(theme.ink, theme.zebra_fill) >= 12.0


@pytest.mark.parametrize(
    "accent",
    ["FFFFFF", "000000", "FF0000", "00FF00", "0000FF", "FFFF00", "00FFFF", "FF00FF",
     "808080", "7F7F00", "123456", "ABCDEF"],
)
def test_any_colour_at_all_still_produces_a_readable_document(accent):
    """Not just the twelve. The picker admits 16.7 million colours and every one must work.

    4.0 rather than 4.5 on the header, and the difference is arithmetic rather than a lowered
    standard: the better of white and near-black on a fill is worst at the crossover luminance
    where the two are equal, which with a near-black rather than a pure black ink lands at 4.16.
    """
    theme = theme_from_accent(accent)
    assert contrast_ratio(theme.table_header_fill, theme.table_header_text) >= 4.0
    assert contrast_ratio(theme.accent, PAPER) >= 3.0
    assert contrast_ratio(theme.accent_soft, PAPER) >= 3.0


def test_a_colour_too_pale_to_read_is_darkened_rather_than_refused():
    """The accent is heading text. Pale yellow headings are not a style, they are absent ones.

    The picker shows the DERIVED accent for exactly this reason — the swatch and the paper have
    to agree about what was chosen — so this behaviour is visible at the moment of choosing and
    not at the moment of printing.
    """
    theme = theme_from_accent("FFE680")
    assert theme.accent != "FFE680"
    assert relative_luminance(theme.accent) < relative_luminance("FFE680")
    assert contrast_ratio(theme.accent, PAPER) >= 3.0


# --------------------------------------------------------------------------------------
# Determinism and the twelve
# --------------------------------------------------------------------------------------


def test_the_derivation_is_deterministic():
    assert theme_from_accent("6B2737") == theme_from_accent("#6b2737")


def test_there_are_twelve_named_presets_and_a_custom_token():
    assert len(ACCENT_PRESETS) == 12
    assert len({preset.key for preset in ACCENT_PRESETS}) == 12
    assert len({preset.hex for preset in ACCENT_PRESETS}) == 12
    assert len({preset.label for preset in ACCENT_PRESETS}) == 12
    assert set(ACCENT_PRESET_ENUM) == {p.key for p in ACCENT_PRESETS} | {CUSTOM_PRESET}
    # The name is the point. A designer picking "Maroon" is choosing something they can describe
    # to the officer who asked for it; a designer picking #802F42 is not.
    assert all(preset.label and not preset.label.isupper() for preset in ACCENT_PRESETS)


def test_the_presets_are_distinguishable_printed_in_greyscale():
    """Many of these reports are printed on a monochrome office laser, where hue is nothing.

    Two accents that differ only in hue come out of that printer as the same grey, and a report
    "in the teal" is then indistinguishable from one "in the maroon" — which matters when the
    colour was chosen to tell two years' submissions apart. So the twelve sit on a CIE L* ladder
    and this pins the rungs apart. L* rather than raw luminance because L* is the perceptual
    axis: the difference between 2% and 4% luminance is visible, the difference between 60% and
    62% is not.
    """
    def lstar(colour: str) -> float:
        y = relative_luminance(colour)
        return 116.0 * (y ** (1 / 3)) - 16.0 if y > 0.008856 else 903.3 * y

    ladder = sorted(lstar(preset.hex) for preset in ACCENT_PRESETS)
    assert min(b - a for a, b in pairwise(ladder)) >= 1.5
    assert ladder[-1] - ladder[0] >= 25.0


# --------------------------------------------------------------------------------------
# Where the colour comes from
# --------------------------------------------------------------------------------------


def test_resolve_accent_prefers_the_request_then_the_stage_then_nothing():
    """The order every other report option is read in, and for the same reason.

    A designer trying three colours before submitting must not have to save stage 20 three times;
    a deployment that has never opened the picker must keep generating exactly the reports it
    generated last year, which is what the ``None`` case protects.
    """
    settings = {"themeAccent": "#6B2737", "themePreset": "MAROON"}
    assert resolve_accent("#1F3864", settings) == "1F3864"
    assert resolve_accent(None, settings) == "6B2737"
    assert resolve_accent(None, {"themePreset": "FOREST"}) == "1D4835"
    assert resolve_accent(None, {}) is None
    assert resolve_accent(None, None) is None


def test_an_unknown_preset_name_resolves_to_nothing_rather_than_to_a_guess():
    """A token from a newer client is not a licence to invent a colour.

    Falling back to the template's palette prints the report in a colour somebody chose; guessing
    prints it in one nobody did, and neither the file nor the screen would say which happened.
    """
    assert resolve_accent(None, {"themePreset": "CHARTREUSE"}) is None
    assert resolve_accent(None, {"themePreset": CUSTOM_PRESET}) is None
    # A malformed hex on the stage falls through to the preset rather than taking the whole
    # choice down with it.
    assert resolve_accent(None, {"themeAccent": "not a colour",
                                 "themePreset": "TEAL"}) == "10616A"


# --------------------------------------------------------------------------------------
# The golden table — what the Kotlin and TypeScript ports are checked against
# --------------------------------------------------------------------------------------

# ReportModel.kt's `themeFromAccent` and lib/reportTheme.ts's `themeFromAccent` must produce
# EVERY ONE of these strings for the same input. They are not a shared library and cannot be: the
# Android report is written on the handset with no server involved. So this table is the contract
# between the three, and a port that disagrees prints the same workshop in a different colour on
# the phone than in the office — which nobody discovers until two copies of one report are on one
# desk. Regenerate it only with a deliberate change to the derivation, and port that change the
# same day.
GOLDEN: tuple[tuple[str, str, str, str, str, str, str, str], ...] = (
    # accent          soft      ink       muted     rule      zebra     header text
    ("152A50", "152A50", "244889", "191A1D", "576989", "B6C3DB", "F2F5FA", "FFFFFF"),
    ("1F3864", "1F3864", "2F5497", "191A1D", "5A6A87", "B8C4D9", "F2F5FA", "FFFFFF"),
    ("1D4835", "1D4835", "327B5B", "191C1B", "5A7C6D", "BBD6CA", "F3F9F6", "FFFFFF"),
    ("672F6A", "672F6A", "914295", "1C191C", "7F6081", "D3BCD5", "F9F3F9", "FFFFFF"),
    ("802F42", "802F42", "AB3F58", "1D191A", "845C66", "D7BAC1", "FAF3F4", "FFFFFF"),
    ("4B5259", "4B5259", "67707A", "1A1B1B", "6D7074", "C6C8CB", "F5F6F7", "FFFFFF"),
    ("10616A", "10616A", "1997A5", "181D1D", "497D83", "B1DBE0", "F1FAFB", "FFFFFF"),
    ("9C4030", "9C4030", "C4523E", "1D1919", "87605A", "D9BDB8", "FAF3F2", "FFFFFF"),
    ("4E638E", "4E638E", "657BAA", "1A1A1C", "646C7D", "BFC5D2", "F4F5F8", "FFFFFF"),
    ("6D6B24", "6D6B24", "999633", "1D1D19", "7A7852", "D8D7B8", "FAFAF2", "FFFFFF"),
    ("87682E", "87682E", "B2893D", "1D1B19", "827459", "D8CDB9", "FAF7F2", "FFFFFF"),
    ("AD5D1C", "AD5D1C", "DB7624", "1D1A18", "8F6D51", "DFC6B2", "FBF6F1", "FFFFFF"),
    # Two off the ladder: a pale accent that must be darkened and must flip the header text, and
    # a pure grey, which exercises the hue-is-undefined branch of the RGB→HSL conversion that a
    # port is most likely to get wrong — and which lands close enough to the crossover luminance
    # to flip the header text as well.
    ("FFE680", "B39000", "B08D00", "1D1C18", "817646", "E1D7B0", "FBF9F1", "1D1C18"),
    ("808080", "808080", "949494", "1B1B1B", "707070", "C8C8C8", "F6F6F6", "1B1B1B"),
)


@pytest.mark.parametrize("row", GOLDEN, ids=[row[0] for row in GOLDEN])
def test_golden_palettes(row):
    accent, expect_accent, soft, ink, muted, rule, zebra, header_text = row
    theme = theme_from_accent(accent)
    assert (theme.accent, theme.accent_soft, theme.ink, theme.muted, theme.rule,
            theme.zebra_fill, theme.table_header_text) == (
        expect_accent, soft, ink, muted, rule, zebra, header_text)
    # The fill is the accent itself and not a derivative of it: a table header in a colour that
    # is merely close to the section heading above it reads as a printing fault.
    assert theme.table_header_fill == theme.accent

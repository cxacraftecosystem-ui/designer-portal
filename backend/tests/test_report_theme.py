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


@pytest.mark.parametrize(
    ("given", "expected"),
    [
        ("#1F3864", "1F3864"),
        ("1f3864", "1F3864"),
        ("  #1f3864  ", "1F3864"),
        ("#1F8", "11FF88"),
        ("", None),
        ("   ", None),
        ("blue", None),
        ("#12345", None),
        ("#1234567", None),
        ("rgb(1,2,3)", None),
        (None, None),
        (7, None),
        (0x1F3864, None),
    ],
)
def test_normalise_hex(given, expected):
    assert normalise_hex(given) == expected



@pytest.mark.parametrize("rubbish", [None, "", "   ", "blue", "#12345", 7, [], {"hex": "#fff"}])
def test_malformed_accent_falls_back_to_the_dch_indigo(rubbish):
    assert theme_from_accent(rubbish).accent == DEFAULT_ACCENT


def test_the_fallback_palette_is_the_one_the_report_has_always_used():
    derived = theme_from_accent(DEFAULT_ACCENT)
    assert derived.rule == DCH_THEME.rule
    assert derived.zebra_fill == DCH_THEME.zebra_fill
    assert derived.table_header_fill == DCH_THEME.table_header_fill
    for ours, theirs in ((derived.accent_soft, DCH_THEME.accent_soft),
                         (derived.muted, DCH_THEME.muted)):
        assert contrast_ratio(ours, theirs) < 1.02


def test_a_base_theme_keeps_its_fonts_and_its_type_size():
    from app.services.report_templates import CATALOGUE_THEME

    derived = theme_from_accent("802F42", base=CATALOGUE_THEME)
    assert derived.base_size_pt == CATALOGUE_THEME.base_size_pt
    assert derived.heading_font == CATALOGUE_THEME.heading_font
    assert derived.accent == "802F42"



def test_a_dark_accent_gets_white_header_text():
    assert theme_from_accent("1F3864").table_header_text == PAPER


@pytest.mark.parametrize("pale", ["FFE680", "F2C744", "E8F0A0", "FFFFFF", "D9F0FF"])
def test_a_pale_accent_gets_dark_header_text(pale):
    theme = theme_from_accent(pale)
    assert theme.table_header_text == theme.ink
    assert theme.table_header_text != PAPER


@pytest.mark.parametrize("accent", [preset.hex for preset in ACCENT_PRESETS])
def test_every_preset_is_legible_where_it_is_used(accent):
    theme = theme_from_accent(accent)
    assert contrast_ratio(theme.table_header_fill, theme.table_header_text) >= 4.5
    assert contrast_ratio(theme.accent, PAPER) >= 4.5
    assert contrast_ratio(theme.accent_soft, PAPER) >= 3.0
    assert contrast_ratio(theme.ink, PAPER) >= 12.0
    assert contrast_ratio(theme.muted, PAPER) >= 4.0
    assert contrast_ratio(theme.zebra_fill, PAPER) < 1.12
    assert contrast_ratio(theme.ink, theme.zebra_fill) >= 12.0


@pytest.mark.parametrize(
    "accent",
    ["FFFFFF", "000000", "FF0000", "00FF00", "0000FF", "FFFF00", "00FFFF", "FF00FF",
     "808080", "7F7F00", "123456", "ABCDEF"],
)
def test_any_colour_at_all_still_produces_a_readable_document(accent):
    theme = theme_from_accent(accent)
    assert contrast_ratio(theme.table_header_fill, theme.table_header_text) >= 4.0
    assert contrast_ratio(theme.accent, PAPER) >= 3.0
    assert contrast_ratio(theme.accent_soft, PAPER) >= 3.0


def test_a_colour_too_pale_to_read_is_darkened_rather_than_refused():
    theme = theme_from_accent("FFE680")
    assert theme.accent != "FFE680"
    assert relative_luminance(theme.accent) < relative_luminance("FFE680")
    assert contrast_ratio(theme.accent, PAPER) >= 3.0



def test_the_derivation_is_deterministic():
    assert theme_from_accent("6B2737") == theme_from_accent("#6b2737")


def test_there_are_twelve_named_presets_and_a_custom_token():
    assert len(ACCENT_PRESETS) == 12
    assert len({preset.key for preset in ACCENT_PRESETS}) == 12
    assert len({preset.hex for preset in ACCENT_PRESETS}) == 12
    assert len({preset.label for preset in ACCENT_PRESETS}) == 12
    assert set(ACCENT_PRESET_ENUM) == {p.key for p in ACCENT_PRESETS} | {CUSTOM_PRESET}
    assert all(preset.label and not preset.label.isupper() for preset in ACCENT_PRESETS)


def test_the_presets_are_distinguishable_printed_in_greyscale():
    def lstar(colour: str) -> float:
        y = relative_luminance(colour)
        return 116.0 * (y ** (1 / 3)) - 16.0 if y > 0.008856 else 903.3 * y

    ladder = sorted(lstar(preset.hex) for preset in ACCENT_PRESETS)
    assert min(b - a for a, b in pairwise(ladder)) >= 1.5
    assert ladder[-1] - ladder[0] >= 25.0



def test_resolve_accent_prefers_the_request_then_the_stage_then_nothing():
    settings = {"themeAccent": "#6B2737", "themePreset": "MAROON"}
    assert resolve_accent("#1F3864", settings) == "1F3864"
    assert resolve_accent(None, settings) == "6B2737"
    assert resolve_accent(None, {"themePreset": "FOREST"}) == "1D4835"
    assert resolve_accent(None, {}) is None
    assert resolve_accent(None, None) is None


def test_an_unknown_preset_name_resolves_to_nothing_rather_than_to_a_guess():
    assert resolve_accent(None, {"themePreset": "CHARTREUSE"}) is None
    assert resolve_accent(None, {"themePreset": CUSTOM_PRESET}) is None
    assert resolve_accent(None, {"themeAccent": "not a colour",
                                 "themePreset": "TEAL"}) == "10616A"


GOLDEN: tuple[tuple[str, str, str, str, str, str, str, str], ...] = (
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
    assert theme.table_header_fill == theme.accent

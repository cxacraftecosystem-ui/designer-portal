"""One accent colour in, a whole legible report palette out.

``ReportTheme`` carries eight colours — accent, accent_soft, ink, muted, rule, table_header_fill,
table_header_text and zebra_fill — and every renderer on every surface reads all eight. This
module exists so that a designer choosing the colour of their report chooses exactly ONE of them
and the other seven are computed.

WHY NOT EIGHT PICKERS. Because a form with eight colour wells is a form that can produce an
illegible government report, and it will: white header text on pale yellow, a zebra fill darker
than the ink sitting on it, a rule the same colour as the paper. Those are not hypothetical —
they are what every "customise your theme" screen produces the first afternoon it ships, and the
person who discovers it is the officer opening the .docx, not the designer who chose it. Deriving
seven colours from one means every reachable choice is a coherent palette, and the set of
unreachable choices is exactly the set of unreadable ones.

The derivation works in HSL, keeping the accent's hue on every derived colour, because that is
what makes a table read as one family: a maroon report has a maroon rule and a maroon-tinted
zebra stripe rather than a grey table with a coloured strip on top. Two things are decided by
measurement rather than by taste, and both are the reason this is a function and not a lookup
table:

**The table header's text is chosen by CONTRAST, never by hue.** ``table_header_fill`` is the
accent itself, and the text on it is white or near-black according to which has the higher WCAG
contrast ratio against that fill. Guessing from the hue — "yellow is light, navy is dark" — gets
mid-tone teals and olives wrong in both directions, and a header row of white 9pt bold on pale
yellow is unreadable on screen and invisible on an office laser.

**Nothing the designer picks can produce invisible text.** The accent is used by all four
renderers as HEADING text on white paper, so it is darkened — hue and saturation untouched —
until it clears a 3:1 contrast ratio against the page, and ``accent_soft`` (H3s and quotes) is
darkened the same way after being lightened. A designer who picks pale yellow gets a slightly
deeper yellow rather than forty pages of headings that are not there. The picker shows the
DERIVED accent for this reason, so the swatch and the paper agree.

:data:`ACCENT_PRESETS` is twelve of these accents with names, because a designer telling the
officer who asked for it that the report is "Maroon" is describing something; "#802F42" is not.
They sit on a deliberate lightness ladder — see that constant — because many of these reports are
printed on a monochrome office laser, where hue is nothing and lightness is everything.

Malformed input NEVER raises. :func:`theme_from_accent` takes anything at all and falls back to
the DCH indigo the report has always been written in, because the alternative is a designer
standing in a district office unable to generate a report at all because a colour string that
reached the record through six years of clients has a stray "#" in it.

The port of this module is ``ReportModel.kt``'s ``themeFromAccent`` (Android, offline export) and
``lib/reportTheme.ts`` (the web preview). All three must agree to the byte for the same accent, or
the same workshop prints in three colours; ``tests/test_report_theme.py`` pins the values the
other two are checked against.
"""

from __future__ import annotations

from dataclasses import dataclass, replace

from app.services.report_model import ReportTheme

# The colour the report has been written in since the first DCH template, and the answer to every
# question this module cannot answer: a missing accent, a malformed one, an empty string, None.
DEFAULT_ACCENT = "1F3864"

# What ``themePreset`` holds when the designer used the colour picker rather than a named swatch.
# The token is stored so the UI can show the picker open on return; the hex in ``themeAccent`` is
# what actually renders, and this token has no effect on any file.
CUSTOM_PRESET = "CUSTOM"


@dataclass(frozen=True, slots=True)
class AccentPreset:
    """One named accent: the token stored on the stage, the words a designer reads, the colour."""

    key: str
    label: str
    hex: str


# --------------------------------------------------------------------------------------
# The twelve
# --------------------------------------------------------------------------------------
#
# Twelve accents that suit a government craft report — nothing fluorescent, nothing pastel, every
# one of them a colour an officer would accept on a submission — ORDERED BY LIGHTNESS AND SPACED
# ON IT, which is the constraint that actually shaped this list.
#
# These reports are printed. Not "may be printed": the reason the .docx exists at all is that a
# district office prints it on a monochrome laser and files the copy. On that printer hue is
# discarded entirely and the accent becomes a grey, so twelve colours that happen to be equally
# dark are twelve reports that come out of the tray identical. Each colour below was therefore
# taken at its own hue and saturation and moved along the lightness axis until it landed on its
# rung of a CIE L* ladder running from 17 (navy) to 48 (burnt orange), so no two are the same grey
# and adjacent rungs differ by roughly two L* — a visible step at 600 dpi. Only the lightness was
# moved: a maroon at L* 32 is still recognisably a maroon, which is what the designer chose.
#
# The top of the ladder is fixed at L* 48 rather than any higher because the accent is HEADING
# TEXT on white paper. Above that a heading falls below a 4.5:1 contrast ratio against the page,
# and a report whose section titles are hard to read in the room they are read out in is not a
# report anyone should be able to produce with two clicks.
#
# INDIGO is pinned to the DCH template's own 1F3864 rather than to its rung of the ladder. It is
# the colour every report generated before this feature existed was written in, and a designer
# choosing "Deep indigo" must get exactly that file back rather than something a shade off it that
# only a side-by-side comparison would reveal.
ACCENT_PRESETS: tuple[AccentPreset, ...] = (
    AccentPreset("NAVY", "Navy", "152A50"),
    AccentPreset("INDIGO", "Deep indigo", DEFAULT_ACCENT),
    AccentPreset("FOREST", "Forest green", "1D4835"),
    AccentPreset("PLUM", "Plum", "672F6A"),
    AccentPreset("MAROON", "Maroon", "802F42"),
    AccentPreset("CHARCOAL", "Charcoal", "4B5259"),
    AccentPreset("TEAL", "Teal", "10616A"),
    AccentPreset("BRICK", "Brick", "9C4030"),
    AccentPreset("SLATE", "Slate blue", "4E638E"),
    AccentPreset("OLIVE", "Olive", "6D6B24"),
    AccentPreset("BRONZE", "Bronze", "87682E"),
    AccentPreset("BURNT_ORANGE", "Burnt orange", "AD5D1C"),
)

PRESETS_BY_KEY: dict[str, AccentPreset] = {preset.key: preset for preset in ACCENT_PRESETS}

# The canonical option list for the stage-20 ``themePreset`` field, installed into
# ``stage_schema.ENUMS`` under REPORT_ACCENT_PRESET so the web form, the Android form and the
# validator all read the same twelve names from the same place. CUSTOM is last because it is not
# a colour: it is the answer "the one in the picker".
ACCENT_PRESET_ENUM: dict[str, str] = {
    **{preset.key: preset.label for preset in ACCENT_PRESETS},
    CUSTOM_PRESET: "Custom colour",
}


# --------------------------------------------------------------------------------------
# Typefaces
# --------------------------------------------------------------------------------------
#
# WHAT A FONT CHOICE CAN AND CANNOT REACH, stated here because the honest answer is "one of the
# two files", and a picker that pretended otherwise would be worse than no picker.
#
#   .docx  HONOURS IT. ``report_docx`` writes the family name into ``w:rFonts`` and Word resolves
#          it on the machine that opens the document. This is the file that is submitted, and the
#          file an officer edits, so it is the one that matters most.
#   .pdf   DOES NOT. ``report_pdf`` chooses its face by probing the filesystem for one that can
#          actually draw Odia, Devanagari and the rupee sign — see the long note at the top of
#          that module. A server that does not have the chosen family installed cannot embed it,
#          and substituting silently would produce a PDF that looks nothing like the .docx beside
#          it. So the generator RAISES A WARNING instead, and the designer is told.
#
# The families below are the ones a Government of India report is actually set in, and every one
# of them is either a Microsoft core font or a metric-compatible free equivalent, so the .docx
# opens looking the same on a departmental Windows machine and on a reviewer's LibreOffice.
FONT_PRESETS: dict[str, tuple[str, str, str]] = {
    # key: (label, heading family, body family)
    "DEFAULT": ("Template default", "", ""),
    "CALIBRI": ("Calibri", "Calibri Light", "Calibri"),
    "CAMBRIA": ("Cambria (serif)", "Cambria", "Cambria"),
    "GEORGIA": ("Georgia (serif)", "Georgia", "Georgia"),
    "TIMES": ("Times New Roman (serif)", "Times New Roman", "Times New Roman"),
    "ARIAL": ("Arial", "Arial", "Arial"),
    "VERDANA": ("Verdana", "Verdana", "Verdana"),
    "GARAMOND": ("Garamond (serif)", "Garamond", "Garamond"),
}

#: The option list for the stage-20 ``fontPreset`` field, installed into ``stage_schema.ENUMS``
#: under REPORT_FONT so the web form, the Android form and the validator read one list.
FONT_PRESET_ENUM: dict[str, str] = {key: value[0] for key, value in FONT_PRESETS.items()}

#: The answer meaning "leave the template's own typeface alone".
DEFAULT_FONT = "DEFAULT"


def resolve_font(requested: object, report_settings: dict[str, object] | None) -> tuple[str, str] | None:
    """The (heading, body) families this report is set in, or ``None`` to leave the template's own.

    Read in the same order as every other report option — see ``wants_transcripts`` and
    ``resolve_accent`` — the request wins if it said anything, the saved stage-20 answer applies
    otherwise, and where neither spoke the answer is ``None`` and the template's fonts stand
    untouched. An unrecognised token resolves to ``None`` rather than to a guess, for the same
    reason an unknown accent preset does: a newer client's font name must not silently produce a
    report in a typeface this build never chose.
    """
    for candidate in (requested, (report_settings or {}).get("fontPreset")):
        token = str(candidate).strip().upper() if candidate not in (None, "") else ""
        if not token or token == DEFAULT_FONT:
            continue
        preset = FONT_PRESETS.get(token)
        if preset and preset[1]:
            return preset[1], preset[2]
    return None


# --------------------------------------------------------------------------------------
# Colour arithmetic
# --------------------------------------------------------------------------------------
#
# Written out by hand rather than taken from ``colorsys``, and the reason is not purity: this is
# the block that has to be ported character for character into Kotlin and TypeScript, neither of
# which has ``colorsys``. A port of six explicit formulas can be diffed against its original by a
# reader; a port of "whatever the standard library did" cannot, and a half-point difference in the
# hue conversion is a report that prints one colour on the phone and another on the server for the
# same workshop.

_HEX_DIGITS = "0123456789abcdefABCDEF"


def normalise_hex(value: object) -> str | None:
    """``value`` as a bare upper-case ``RRGGBB``, or ``None`` if it is not a colour at all.

    Accepts what the three surfaces actually send: ``#1F3864`` from ``<input type="color">``,
    ``1f3864`` from a hand-typed field, ``#1F8`` from a designer who knows CSS shorthand, and
    surrounding whitespace from every one of them. Everything else — a colour name, an rgb()
    string, ``None``, a number, the empty string — is not a colour, and the caller falls back
    rather than guessing what was meant.
    """
    if not isinstance(value, str):
        return None
    text = value.strip().lstrip("#").strip()
    if not text or any(ch not in _HEX_DIGITS for ch in text):
        return None
    if len(text) == 3:
        # CSS shorthand: each digit doubles. #1F8 is #11FF88, not #001F08.
        text = "".join(ch * 2 for ch in text)
    if len(text) != 6:
        return None
    return text.upper()


def _to_rgb(hex_colour: str) -> tuple[float, float, float]:
    """``RRGGBB`` to three 0..1 channels. Only ever called with :func:`normalise_hex` output."""
    return tuple(int(hex_colour[i:i + 2], 16) / 255.0 for i in (0, 2, 4))  # type: ignore[return-value]


def _to_hex(rgb: tuple[float, float, float]) -> str:
    """Three 0..1 channels back to ``RRGGBB``, clamped and rounded half-up.

    Clamping matters: a lightness of 1.0 with any saturation produces channels a hair over 1.0
    through the floating-point path below, and ``format(256, '02X')`` is ``'100'`` — a seven-digit
    colour that Word accepts, draws black, and gives no error for.

    ``int(x + 0.5)`` and not ``round(x)``, which is the one line of this module that would
    otherwise be unportable: Python rounds halves to even, Kotlin's ``roundToInt`` and JavaScript's
    ``Math.round`` round them up, and a channel landing exactly on .5 would come out one digit
    apart on the phone and on the server. Half-up is what all three do when it is written out.
    """
    return "".join(f"{int(max(0.0, min(1.0, channel)) * 255 + 0.5):02X}" for channel in rgb)


def _rgb_to_hsl(rgb: tuple[float, float, float]) -> tuple[float, float, float]:
    """(hue in 0..360, saturation 0..1, lightness 0..1). Hue is 0 for any grey."""
    red, green, blue = rgb
    high, low = max(rgb), min(rgb)
    lightness = (high + low) / 2.0
    span = high - low
    if span == 0.0:
        return 0.0, 0.0, lightness
    saturation = span / (2.0 - high - low) if lightness > 0.5 else span / (high + low)
    if high == red:
        hue = ((green - blue) / span) % 6.0
    elif high == green:
        hue = (blue - red) / span + 2.0
    else:
        hue = (red - green) / span + 4.0
    return hue * 60.0, saturation, lightness


def _hsl_to_rgb(hue: float, saturation: float, lightness: float) -> tuple[float, float, float]:
    """The inverse of :func:`_rgb_to_hsl`, in the same explicit form so the two can be read as a pair."""
    if saturation <= 0.0:
        return lightness, lightness, lightness
    chroma = (1.0 - abs(2.0 * lightness - 1.0)) * saturation
    sector = (hue % 360.0) / 60.0
    second = chroma * (1.0 - abs(sector % 2.0 - 1.0))
    if sector < 1.0:
        triple = (chroma, second, 0.0)
    elif sector < 2.0:
        triple = (second, chroma, 0.0)
    elif sector < 3.0:
        triple = (0.0, chroma, second)
    elif sector < 4.0:
        triple = (0.0, second, chroma)
    elif sector < 5.0:
        triple = (second, 0.0, chroma)
    else:
        triple = (chroma, 0.0, second)
    base = lightness - chroma / 2.0
    return triple[0] + base, triple[1] + base, triple[2] + base


def _hsl_hex(hue: float, saturation: float, lightness: float) -> str:
    return _to_hex(_hsl_to_rgb(hue, max(0.0, min(1.0, saturation)),
                               max(0.0, min(1.0, lightness))))


def relative_luminance(hex_colour: str) -> float:
    """WCAG 2.1 relative luminance of a ``RRGGBB``.

    The whole point of computing this rather than eyeballing the hex is the mid-tones: a teal at
    ``10616A`` and an olive at ``6D6B24`` look equally "medium" and are not, and the table header
    text that suits one is wrong on the other. Human judgement about lightness from three hex
    pairs is not merely imprecise, it is systematically wrong — green carries seven times the
    luminance of blue for the same channel value, which is exactly what the coefficients say.
    """
    def linear(channel: float) -> float:
        return channel / 12.92 if channel <= 0.04045 else ((channel + 0.055) / 1.055) ** 2.4

    red, green, blue = (linear(c) for c in _to_rgb(hex_colour))
    return 0.2126 * red + 0.7152 * green + 0.0722 * blue


def contrast_ratio(first: str, second: str) -> float:
    """The WCAG contrast ratio between two ``RRGGBB`` colours: 1.0 (identical) to 21.0 (black/white)."""
    a, b = relative_luminance(first), relative_luminance(second)
    lighter, darker = (a, b) if a >= b else (b, a)
    return (lighter + 0.05) / (darker + 0.05)


# --------------------------------------------------------------------------------------
# The derivation
# --------------------------------------------------------------------------------------

# The page. Every legibility decision below is taken against white because that is what the
# report is printed on; the preview's paper is white for the same reason.
PAPER = "FFFFFF"

# The floor for anything used as TEXT on that paper. 3:1 is the WCAG bar for large text, and
# every use of ``accent`` and ``accent_soft`` in all four renderers is large: H1 at 17pt, H2 at
# 13.5, H3 at 11.5, the metric numbers at 17, the figure titles bold. Body prose is ``ink`` and
# is near-black regardless of what the designer picked.
MIN_TEXT_CONTRAST = 3.0

# The floor for ``muted``, which is NOT large text: it draws the running head and foot at 7.8pt
# and the NOTE paragraphs that carry provenance and caveats. 4.5:1 is the WCAG bar for text at
# that size, and it is the one a mid-tone teal or olive fails at the fixed lightness below — the
# hues that carry the most luminance for the same lightness are exactly the ones whose muted grey
# comes out too pale to read a page number in.
MIN_SMALL_TEXT_CONTRAST = 4.5

# How far ``accent_soft`` is lifted toward the page: 17.5% of the distance from the accent's own
# lightness to white. Not a fixed number of lightness points, because a lift that reads as one
# step down from a navy would take a burnt orange off the top of the page entirely.
#
# The figure is not arbitrary — it is the one that reproduces the DCH template's hand-picked pair
# (1F3864 accent, 2F5496 soft) to within one hex digit, and the DIC and agency templates likewise.
# Those three pairs were chosen by eye by somebody looking at printed reports, and matching them
# is the closest thing this module has to a ground truth.
_SOFT_LIFT = 0.175

# The soft accent is a mid-tone by construction; without this it is not. A pale accent lifted
# 17.5% further toward white is a colour with nothing left, and every H3 and every artisan quote
# in a forty-page report would be drawn in it.
_SOFT_MAX_LIGHTNESS = 0.62

# ``ink``, ``muted``, ``rule`` and ``zebra_fill`` are the accent's own hue at a fixed lightness and
# a fraction of its saturation. The saturation caps are what keep a fully-saturated accent from
# producing a lurid table: at 96.5% lightness a saturation of 1.0 is still a visible tint, and the
# cap turns "the accent at very low saturation" into a promise rather than a hope. Each pair is
# again the one that reproduces the three hand-picked template palettes.
_INK_SATURATION, _INK_LIGHTNESS = (0.14, 0.10), 0.105
_MUTED_SATURATION, _MUTED_LIGHTNESS = (0.38, 0.30), 0.44
_RULE_SATURATION, _RULE_LIGHTNESS = (0.58, 0.45), 0.786
_ZEBRA_SATURATION, _ZEBRA_LIGHTNESS = (0.84, 0.60), 0.965


def _tinted(hue: float, saturation: float, scale_and_cap: tuple[float, float],
            lightness: float) -> str:
    scale, cap = scale_and_cap
    return _hsl_hex(hue, min(saturation * scale, cap), lightness)


def _legible_on_paper(hue: float, saturation: float, lightness: float,
                      minimum: float = MIN_TEXT_CONTRAST) -> str:
    """The colour at this hue and saturation, darkened until it can be read on the page.

    Steps the lightness down one hundredth at a time rather than solving for it. The loop is
    deterministic, terminates (black clears 3:1 against white by a factor of seven), and — the
    reason it is a loop and not a binary search — reads and ports identically into Kotlin and
    TypeScript. A binary search over floating point in three languages is three subtly different
    answers in the last hex digit, which on a table header fill is a visible seam between the
    server's PDF and the phone's.

    The alternative to darkening was refusing the colour, and that is worse: a designer in a
    district office is not looking for a lecture about contrast ratios, they are looking for a
    report. This gives them their colour, as close to what they picked as legibility allows.
    """
    # Assigned before the loop although the first iteration always overwrites it: Kotlin's `var`
    # needs an initialiser and the three implementations are kept line-for-line identical, which
    # is worth more here than one saved statement.
    candidate = _hsl_hex(hue, saturation, lightness)
    for step in range(101):
        candidate = _hsl_hex(hue, saturation, lightness - step * 0.01)
        if contrast_ratio(candidate, PAPER) >= minimum:
            break
    return candidate


def theme_from_accent(accent: object, *, base: ReportTheme | None = None) -> ReportTheme:
    """A complete, coherent, legible :class:`ReportTheme` from one accent colour.

    ``base`` supplies everything that is not a colour — the heading, body and complex-script font
    names and the base point size — so a template that sets its catalogue text at 10pt keeps it
    when a designer recolours the report. Passing no base gives the model's own defaults.

    Never raises. ``None``, ``""``, ``"blue"``, ``"#12345"`` and a stray integer all produce the
    DCH indigo palette, because the caller is a report generation the designer is waiting on and
    the correct behaviour for a bad colour string is a report in the old colour, not no report.
    """
    hex_colour = normalise_hex(accent) or DEFAULT_ACCENT
    hue, saturation, lightness = _rgb_to_hsl(_to_rgb(hex_colour))

    # The accent, darkened only as far as it must be. For all twelve presets and for any colour a
    # designer is likely to choose this returns the input unchanged; it moves only for the pale
    # ones, and the picker shows the result rather than the request so the swatch and the paper
    # agree about what was chosen.
    accent_hex = _legible_on_paper(hue, saturation, lightness)
    _, _, accent_lightness = _rgb_to_hsl(_to_rgb(accent_hex))

    soft_lightness = min(accent_lightness + (1.0 - accent_lightness) * _SOFT_LIFT,
                         _SOFT_MAX_LIGHTNESS)
    soft_hex = _legible_on_paper(hue, saturation, soft_lightness)

    ink_hex = _tinted(hue, saturation, _INK_SATURATION, _INK_LIGHTNESS)

    # White or near-black on the header row, by measurement. The near-black option is the
    # document's own ink rather than pure black, so a header that reverses to dark text is the
    # same dark as the prose under it instead of a second, harder black nobody chose.
    header_text = (
        PAPER if contrast_ratio(accent_hex, PAPER) >= contrast_ratio(accent_hex, ink_hex)
        else ink_hex
    )

    return replace(
        base or ReportTheme(),
        accent=accent_hex,
        accent_soft=soft_hex,
        ink=ink_hex,
        # Darkened where the hue needs it. A page number in the running foot is 7.8pt, and a
        # teal-tinted grey that clears the bar for a heading does not clear it for that.
        muted=_legible_on_paper(hue, min(saturation * _MUTED_SATURATION[0], _MUTED_SATURATION[1]),
                                _MUTED_LIGHTNESS, MIN_SMALL_TEXT_CONTRAST),
        rule=_tinted(hue, saturation, _RULE_SATURATION, _RULE_LIGHTNESS),
        table_header_fill=accent_hex,
        table_header_text=header_text,
        zebra_fill=_tinted(hue, saturation, _ZEBRA_SATURATION, _ZEBRA_LIGHTNESS),
    )


def resolve_accent(requested: object, report_settings: dict[str, object] | None) -> str | None:
    """Which accent this report is written in, or ``None`` to leave the template's own alone.

    Read in the same order as every other report option — see ``wants_transcripts`` — the request
    wins if it said anything, the saved stage-20 answer applies otherwise, and where neither
    spoke the answer is ``None`` and the template's palette stands untouched. That last case is
    what keeps a deployment which has never opened this picker generating byte-identical reports
    to the ones it generated last year.

    On the stage, ``themeAccent`` (the hex) is the authority and ``themePreset`` (the name) is
    read only when it is missing. Both are stored because the name is what a designer recognises
    on their return to the screen, but a preset whose hex this build does not know — a token from
    a newer client — must not silently produce a differently-coloured report, so an unknown name
    resolves to nothing rather than to a guess.
    """
    from_request = normalise_hex(requested)
    if from_request:
        return from_request
    settings = report_settings or {}
    from_stage = normalise_hex(settings.get("themeAccent"))
    if from_stage:
        return from_stage
    preset = settings.get("themePreset")
    if isinstance(preset, str) and preset in PRESETS_BY_KEY:
        return PRESETS_BY_KEY[preset].hex
    return None

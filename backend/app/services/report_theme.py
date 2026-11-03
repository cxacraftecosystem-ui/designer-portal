from __future__ import annotations

from dataclasses import dataclass, replace

from app.services.report_model import ReportTheme

DEFAULT_ACCENT = "1F3864"

CUSTOM_PRESET = "CUSTOM"


@dataclass(frozen=True, slots=True)
class AccentPreset:
    key: str
    label: str
    hex: str


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

ACCENT_PRESET_ENUM: dict[str, str] = {
    **{preset.key: preset.label for preset in ACCENT_PRESETS},
    CUSTOM_PRESET: "Custom colour",
}


FONT_PRESETS: dict[str, tuple[str, str, str]] = {
    "DEFAULT": ("Template default", "", ""),
    "CALIBRI": ("Calibri", "Calibri Light", "Calibri"),
    "CAMBRIA": ("Cambria (serif)", "Cambria", "Cambria"),
    "GEORGIA": ("Georgia (serif)", "Georgia", "Georgia"),
    "TIMES": ("Times New Roman (serif)", "Times New Roman", "Times New Roman"),
    "ARIAL": ("Arial", "Arial", "Arial"),
    "VERDANA": ("Verdana", "Verdana", "Verdana"),
    "GARAMOND": ("Garamond (serif)", "Garamond", "Garamond"),
}

FONT_PRESET_ENUM: dict[str, str] = {key: value[0] for key, value in FONT_PRESETS.items()}

DEFAULT_FONT = "DEFAULT"


def resolve_font(
    requested: object, report_settings: dict[str, object] | None
) -> tuple[str, str] | None:
    for candidate in (requested, (report_settings or {}).get("fontPreset")):
        token = str(candidate).strip().upper() if candidate not in (None, "") else ""
        if not token or token == DEFAULT_FONT:
            continue
        preset = FONT_PRESETS.get(token)
        if preset and preset[1]:
            return preset[1], preset[2]
    return None


_HEX_DIGITS = "0123456789abcdefABCDEF"


def normalise_hex(value: object) -> str | None:
    if not isinstance(value, str):
        return None
    text = value.strip().lstrip("#").strip()
    if not text or any(ch not in _HEX_DIGITS for ch in text):
        return None
    if len(text) == 3:
        text = "".join(ch * 2 for ch in text)
    if len(text) != 6:
        return None
    return text.upper()


def _to_rgb(hex_colour: str) -> tuple[float, float, float]:
    return tuple(int(hex_colour[i : i + 2], 16) / 255.0 for i in (0, 2, 4))  # type: ignore[return-value]


def _to_hex(rgb: tuple[float, float, float]) -> str:
    return "".join(f"{int(max(0.0, min(1.0, channel)) * 255 + 0.5):02X}" for channel in rgb)


def _rgb_to_hsl(rgb: tuple[float, float, float]) -> tuple[float, float, float]:
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
    return _to_hex(_hsl_to_rgb(hue, max(0.0, min(1.0, saturation)), max(0.0, min(1.0, lightness))))


def relative_luminance(hex_colour: str) -> float:
    def linear(channel: float) -> float:
        return channel / 12.92 if channel <= 0.04045 else ((channel + 0.055) / 1.055) ** 2.4

    red, green, blue = (linear(c) for c in _to_rgb(hex_colour))
    return 0.2126 * red + 0.7152 * green + 0.0722 * blue


def contrast_ratio(first: str, second: str) -> float:
    a, b = relative_luminance(first), relative_luminance(second)
    lighter, darker = (a, b) if a >= b else (b, a)
    return (lighter + 0.05) / (darker + 0.05)


PAPER = "FFFFFF"

MIN_TEXT_CONTRAST = 3.0

MIN_SMALL_TEXT_CONTRAST = 4.5

_SOFT_LIFT = 0.175

_SOFT_MAX_LIGHTNESS = 0.62

_INK_SATURATION, _INK_LIGHTNESS = (0.14, 0.10), 0.105
_MUTED_SATURATION, _MUTED_LIGHTNESS = (0.38, 0.30), 0.44
_RULE_SATURATION, _RULE_LIGHTNESS = (0.58, 0.45), 0.786
_ZEBRA_SATURATION, _ZEBRA_LIGHTNESS = (0.84, 0.60), 0.965


def _tinted(
    hue: float, saturation: float, scale_and_cap: tuple[float, float], lightness: float
) -> str:
    scale, cap = scale_and_cap
    return _hsl_hex(hue, min(saturation * scale, cap), lightness)


def _legible_on_paper(
    hue: float, saturation: float, lightness: float, minimum: float = MIN_TEXT_CONTRAST
) -> str:
    candidate = _hsl_hex(hue, saturation, lightness)
    for step in range(101):
        candidate = _hsl_hex(hue, saturation, lightness - step * 0.01)
        if contrast_ratio(candidate, PAPER) >= minimum:
            break
    return candidate


def theme_from_accent(accent: object, *, base: ReportTheme | None = None) -> ReportTheme:
    hex_colour = normalise_hex(accent) or DEFAULT_ACCENT
    hue, saturation, lightness = _rgb_to_hsl(_to_rgb(hex_colour))

    accent_hex = _legible_on_paper(hue, saturation, lightness)
    _, _, accent_lightness = _rgb_to_hsl(_to_rgb(accent_hex))

    soft_lightness = min(
        accent_lightness + (1.0 - accent_lightness) * _SOFT_LIFT, _SOFT_MAX_LIGHTNESS
    )
    soft_hex = _legible_on_paper(hue, saturation, soft_lightness)

    ink_hex = _tinted(hue, saturation, _INK_SATURATION, _INK_LIGHTNESS)

    header_text = (
        PAPER
        if contrast_ratio(accent_hex, PAPER) >= contrast_ratio(accent_hex, ink_hex)
        else ink_hex
    )

    return replace(
        base or ReportTheme(),
        accent=accent_hex,
        accent_soft=soft_hex,
        ink=ink_hex,
        muted=_legible_on_paper(
            hue,
            min(saturation * _MUTED_SATURATION[0], _MUTED_SATURATION[1]),
            _MUTED_LIGHTNESS,
            MIN_SMALL_TEXT_CONTRAST,
        ),
        rule=_tinted(hue, saturation, _RULE_SATURATION, _RULE_LIGHTNESS),
        table_header_fill=accent_hex,
        table_header_text=header_text,
        zebra_fill=_tinted(hue, saturation, _ZEBRA_SATURATION, _ZEBRA_LIGHTNESS),
    )


def resolve_accent(requested: object, report_settings: dict[str, object] | None) -> str | None:
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

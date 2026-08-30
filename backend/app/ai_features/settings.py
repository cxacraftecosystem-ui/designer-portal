"""Configuration for the AI image features, read WITHOUT touching ``app.core.config``.

WHY A SEPARATE READER. This package is dormant: nothing in the API imports it, every capability
defaults to off, and none of its dependencies are installed on the production box. Adding a dozen
fields to the global ``Settings`` object would make every boot of every process parse and carry
configuration for a feature that is not running, and would couple a strictly optional package to
the file that must never fail to load. So the flags live here, are read on first use rather than
at import, and are cached until something asks for a reset.

This is a deliberate staging post, not a rival configuration system. When the features are turned
on for real, move ``ENV_VARS`` into ``Settings`` verbatim (the aliases are already the env-var
names) and make ``load()`` read from it — every other module in this package goes through
``get_ai_settings()``, so that is a one-file change. ``docs/AI_FEATURES.md`` lists the exact
fields to add.

Precedence matches the rest of the backend: a real environment variable wins, and anything absent
from the environment falls back to ``backend/.env`` — the same file pydantic-settings reads — so
an operator who sets ``AI_FEATURES_ENABLED=true`` in the one .env they already maintain gets what
they expect instead of silent nothing.
"""

import logging
import os
from collections.abc import Mapping
from dataclasses import dataclass, field
from functools import lru_cache
from pathlib import Path

from app.ai_features.types import Capability

logger = logging.getLogger(__name__)

#: Every variable this package reads, in documentation order. The probe and the .env reader both
#: iterate this, so a new setting cannot be added without becoming visible to operators.
ENV_VARS: tuple[str, ...] = (
    "AI_FEATURES_ENABLED",
    "AI_BACKGROUND_REMOVAL_ENABLED",
    "AI_FOREGROUND_SEPARATION_ENABLED",
    "AI_IMAGE_VECTORISATION_ENABLED",
    "AI_MARKET_RESEARCH_ENABLED",
    "AI_BACKGROUND_REMOVAL_PROVIDER",
    "AI_FOREGROUND_SEPARATION_PROVIDER",
    "AI_IMAGE_VECTORISATION_PROVIDER",
    "AI_MARKET_RESEARCH_PROVIDER",
    "AI_FEATURES_MAX_IMAGE_BYTES",
    "AI_FEATURES_MAX_IMAGE_PIXELS",
    "AI_FEATURES_TIMEOUT_SECONDS",
    "AI_FEATURES_LOCAL_MODEL",
    "AI_FEATURES_LOCAL_MODEL_DIR",
    "AI_FEATURES_CACHE_LOCAL_SESSION",
    "REMOVE_BG_API_KEY",
    "REMOVE_BG_ENDPOINT",
    "REMOVE_BG_SIZE",
    "VECTORIZER_AI_API_ID",
    "VECTORIZER_AI_API_SECRET",
    "VECTORIZER_AI_ENDPOINT",
    "VECTORIZER_AI_MODE",
    "AI_VECTOR_COLORMODE",
    "AI_VECTOR_FILTER_SPECKLE",
    "AI_VECTOR_COLOR_PRECISION",
    "AI_MARKET_RESEARCH_MAX_RESULTS",
    "AI_MARKET_RESEARCH_MAX_QUERIES",
    "AI_MARKET_RESEARCH_MIN_INTERVAL_SECONDS",
    "AI_MARKET_RESEARCH_CURRENCY",
    "AI_MARKET_RESEARCH_COUNTRY",
    "AI_MARKET_RESEARCH_LANGUAGE",
    "AI_MARKET_RESEARCH_USER_AGENT",
    "AI_MARKET_RESEARCH_CATALOGUE_PATH",
    "SERPER_API_KEY",
    "SERPER_ENDPOINT",
    "SERPAPI_API_KEY",
    "SERPAPI_ENDPOINT",
)

#: The two variables that gate and steer each capability. Errors and the probe quote these names
#: literally, because "the feature is disabled" is useless to an operator who then has to guess
#: which of three similarly named flags to set — and the vectorisation pair does not follow the
#: pattern the other two share.
CAPABILITY_ENV_VARS: dict[Capability, tuple[str, str]] = {
    Capability.BACKGROUND_REMOVAL: (
        "AI_BACKGROUND_REMOVAL_ENABLED",
        "AI_BACKGROUND_REMOVAL_PROVIDER",
    ),
    Capability.FOREGROUND_SEPARATION: (
        "AI_FOREGROUND_SEPARATION_ENABLED",
        "AI_FOREGROUND_SEPARATION_PROVIDER",
    ),
    Capability.VECTORISATION: (
        "AI_IMAGE_VECTORISATION_ENABLED",
        "AI_IMAGE_VECTORISATION_PROVIDER",
    ),
    Capability.MARKET_RESEARCH: (
        "AI_MARKET_RESEARCH_ENABLED",
        "AI_MARKET_RESEARCH_PROVIDER",
    ),
}


def enable_var(capability: Capability) -> str:
    """The variable that turns one capability on, given the master switch is also on."""
    return CAPABILITY_ENV_VARS[capability][0]


def provider_var(capability: Capability) -> str:
    """The variable that chooses a provider for one capability."""
    return CAPABILITY_ENV_VARS[capability][1]


# remove.bg refuses inputs over 12 MB, so matching its ceiling means a rejection here reads the
# same as a rejection there. It is also roughly a 24-megapixel JPEG straight off a field camera.
_DEFAULT_MAX_BYTES = 12 * 1024 * 1024
# Decompression-bomb guard, checked from the header before anything decodes the pixels. 24 MP is
# larger than any camera the field teams use and small enough that a local matte cannot swallow
# the box: RGBA at 24 MP is ~96 MB per copy, and matting holds several.
_DEFAULT_MAX_PIXELS = 24_000_000
_DEFAULT_TIMEOUT_SECONDS = 60.0

#: Enough listings for the price floor in market_research to be reachable twice over, and few
#: enough that one brief cannot become a hundred credits. Raising it costs money per call.
_DEFAULT_MARKET_MAX_RESULTS = 20
#: Queries issued per brief. Three is one broad and two narrowed; more is how a single button
#: press becomes a bill nobody predicted.
_DEFAULT_MARKET_MAX_QUERIES = 3
#: Seconds between outbound market-research calls, process-wide. Not a vendor requirement — a
#: courtesy floor, so a batch of briefs cannot look like an attack from an address the vendor will
#: then block for everybody sharing it.
_DEFAULT_MARKET_MIN_INTERVAL = 1.0
#: Identifies us to the vendor. An operator who leaves this alone is still identifiable as this
#: software; the point of overriding it is to add a human to contact before blocking the address.
_DEFAULT_MARKET_USER_AGENT = (
    "DesignWorkshopBackend/0.1 (craft market research; "
    "set AI_MARKET_RESEARCH_USER_AGENT to add a contact address)"
)

_TRUE = frozenset({"1", "true", "yes", "on", "y", "t"})
_FALSE = frozenset({"0", "false", "no", "off", "n", "f", ""})


@dataclass(frozen=True)
class AiFeatureSettings:
    """Resolved configuration. Construct through :func:`build_settings`, never by hand."""

    enabled: bool = False
    background_removal_enabled: bool = False
    foreground_separation_enabled: bool = False
    vectorisation_enabled: bool = False
    market_research_enabled: bool = False

    background_removal_provider: str = "auto"
    foreground_separation_provider: str = "auto"
    vectorisation_provider: str = "auto"
    market_research_provider: str = "auto"

    max_image_bytes: int = _DEFAULT_MAX_BYTES
    max_image_pixels: int = _DEFAULT_MAX_PIXELS
    timeout_seconds: float = _DEFAULT_TIMEOUT_SECONDS

    local_model: str = "u2net"
    local_model_dir: str | None = None
    cache_local_session: bool = True

    remove_bg_api_key: str | None = None
    remove_bg_endpoint: str = "https://api.remove.bg/v1.0/removebg"
    remove_bg_size: str = "auto"

    vectorizer_api_id: str | None = None
    vectorizer_api_secret: str | None = None
    vectorizer_endpoint: str = "https://vectorizer.ai/api/v1/vectorize"
    # "test" is free and returns a watermarked result; "production" spends a credit per image.
    # Defaulting to the free mode means a mis-wired experiment cannot quietly run up a bill.
    vectorizer_mode: str = "test"

    vector_colormode: str = "color"
    vector_filter_speckle: int = 4
    vector_color_precision: int = 6

    market_research_max_results: int = _DEFAULT_MARKET_MAX_RESULTS
    market_research_max_queries: int = _DEFAULT_MARKET_MAX_QUERIES
    market_research_min_interval_seconds: float = _DEFAULT_MARKET_MIN_INTERVAL
    #: The currency retrieved prices are expected in. NOT a conversion target — nothing in this
    #: package converts between currencies. See market_research.py on why that is a refusal.
    market_research_currency: str = "INR"
    market_research_country: str = "in"
    market_research_language: str = "en"
    market_research_user_agent: str = _DEFAULT_MARKET_USER_AGENT
    market_research_catalogue_path: str | None = None

    serper_api_key: str | None = None
    serper_endpoint: str = "https://google.serper.dev/shopping"
    serpapi_api_key: str | None = None
    serpapi_endpoint: str = "https://serpapi.com/search.json"

    #: Human-readable complaints about malformed values, surfaced by the probe rather than thrown.
    #: A typo in a number must never stop the app; it falls back to the default and says so.
    notes: tuple[str, ...] = field(default=())

    def capability_enabled(self, capability: Capability) -> bool:
        """True only when the master switch AND the capability's own flag are on.

        Two levels because the master switch is the thing an incident responder flips: one
        variable turns the whole package off without having to know which of three features is
        misbehaving.
        """
        if not self.enabled:
            return False
        return {
            Capability.BACKGROUND_REMOVAL: self.background_removal_enabled,
            Capability.FOREGROUND_SEPARATION: self.foreground_separation_enabled,
            Capability.VECTORISATION: self.vectorisation_enabled,
            Capability.MARKET_RESEARCH: self.market_research_enabled,
        }[capability]

    def provider_choice(self, capability: Capability) -> str:
        """Configured provider id for a capability, or ``"auto"``."""
        return {
            Capability.BACKGROUND_REMOVAL: self.background_removal_provider,
            Capability.FOREGROUND_SEPARATION: self.foreground_separation_provider,
            Capability.VECTORISATION: self.vectorisation_provider,
            Capability.MARKET_RESEARCH: self.market_research_provider,
        }[capability]

    def value_for(self, env_var: str) -> str | None:
        """Current value of one of :data:`ENV_VARS`, for readiness checks and the probe.

        Secrets are returned as-is; the probe only ever asks "is this non-empty", and never
        prints what it got back.
        """
        return {
            "REMOVE_BG_API_KEY": self.remove_bg_api_key,
            "REMOVE_BG_ENDPOINT": self.remove_bg_endpoint,
            "VECTORIZER_AI_API_ID": self.vectorizer_api_id,
            "VECTORIZER_AI_API_SECRET": self.vectorizer_api_secret,
            "VECTORIZER_AI_ENDPOINT": self.vectorizer_endpoint,
            "SERPER_API_KEY": self.serper_api_key,
            "SERPER_ENDPOINT": self.serper_endpoint,
            "SERPAPI_API_KEY": self.serpapi_api_key,
            "SERPAPI_ENDPOINT": self.serpapi_endpoint,
            "AI_MARKET_RESEARCH_CATALOGUE_PATH": self.market_research_catalogue_path,
        }.get(env_var)


def _as_bool(raw: str | None, default: bool, name: str, notes: list[str]) -> bool:
    if raw is None:
        return default
    value = raw.strip().lower()
    if value in _TRUE:
        return True
    if value in _FALSE:
        return False
    notes.append(f"{name}={raw!r} is not a boolean; using {default}")
    return default


def _as_int(raw: str | None, default: int, name: str, notes: list[str], minimum: int = 1) -> int:
    if raw is None or not raw.strip():
        return default
    try:
        value = int(raw.strip())
    except ValueError:
        notes.append(f"{name}={raw!r} is not an integer; using {default}")
        return default
    if value < minimum:
        notes.append(f"{name}={value} is below the minimum {minimum}; using {minimum}")
        return minimum
    return value


def _as_float(raw: str | None, default: float, name: str, notes: list[str]) -> float:
    if raw is None or not raw.strip():
        return default
    try:
        value = float(raw.strip())
    except ValueError:
        notes.append(f"{name}={raw!r} is not a number; using {default}")
        return default
    if value <= 0:
        notes.append(f"{name}={value} must be positive; using {default}")
        return default
    return value


def _as_seconds(raw: str | None, default: float, name: str, notes: list[str]) -> float:
    """Like :func:`_as_float` but zero is a legitimate answer.

    Separate from ``_as_float`` because the values it reads (a timeout) are meaningless at zero,
    while the value this reads (a courtesy delay between outbound calls) has an obvious meaning:
    do not wait. An operator who has been given a generous quota should be able to say so without
    the setting silently reverting to one second per call.
    """
    if raw is None or not raw.strip():
        return default
    try:
        value = float(raw.strip())
    except ValueError:
        notes.append(f"{name}={raw!r} is not a number; using {default}")
        return default
    if value < 0:
        notes.append(f"{name}={value} cannot be negative; using {default}")
        return default
    return value


def _as_currency(raw: str | None, default: str, name: str, notes: list[str]) -> str:
    """A three-letter ISO 4217 code, upper-cased. Not validated against a list of real ones.

    Checking the shape catches the typo that matters (a currency symbol or a country code where a
    code belongs); checking membership of a list would mean shipping and maintaining that list for
    a package that never converts between currencies anyway.
    """
    value = (raw or "").strip().upper()
    if not value:
        return default
    if len(value) != 3 or not value.isalpha():
        notes.append(f"{name}={raw!r} is not a three-letter currency code; using {default}")
        return default
    return value


def _as_str(raw: str | None, default: str) -> str:
    value = (raw or "").strip()
    return value or default


def _as_optional_str(raw: str | None) -> str | None:
    value = (raw or "").strip()
    return value or None


def _as_choice(
    raw: str | None, default: str, allowed: frozenset[str], name: str, notes: list[str]
) -> str:
    value = (raw or "").strip().lower()
    if not value:
        return default
    if value not in allowed:
        notes.append(f"{name}={raw!r} is not one of {sorted(allowed)}; using {default}")
        return default
    return value


def build_settings(env: Mapping[str, str]) -> AiFeatureSettings:
    """Pure translation of a mapping into settings. No file access, no caching, no side effects.

    Kept pure so the tests can assert the default-off contract against ``{}`` without depending
    on whatever happens to be in the developer's environment or .env file.
    """
    notes: list[str] = []
    get = env.get

    return AiFeatureSettings(
        enabled=_as_bool(get("AI_FEATURES_ENABLED"), False, "AI_FEATURES_ENABLED", notes),
        background_removal_enabled=_as_bool(
            get("AI_BACKGROUND_REMOVAL_ENABLED"), False, "AI_BACKGROUND_REMOVAL_ENABLED", notes
        ),
        foreground_separation_enabled=_as_bool(
            get("AI_FOREGROUND_SEPARATION_ENABLED"),
            False,
            "AI_FOREGROUND_SEPARATION_ENABLED",
            notes,
        ),
        vectorisation_enabled=_as_bool(
            get("AI_IMAGE_VECTORISATION_ENABLED"), False, "AI_IMAGE_VECTORISATION_ENABLED", notes
        ),
        market_research_enabled=_as_bool(
            get("AI_MARKET_RESEARCH_ENABLED"), False, "AI_MARKET_RESEARCH_ENABLED", notes
        ),
        background_removal_provider=_as_str(get("AI_BACKGROUND_REMOVAL_PROVIDER"), "auto").lower(),
        foreground_separation_provider=_as_str(
            get("AI_FOREGROUND_SEPARATION_PROVIDER"), "auto"
        ).lower(),
        vectorisation_provider=_as_str(get("AI_IMAGE_VECTORISATION_PROVIDER"), "auto").lower(),
        market_research_provider=_as_str(get("AI_MARKET_RESEARCH_PROVIDER"), "auto").lower(),
        max_image_bytes=_as_int(
            get("AI_FEATURES_MAX_IMAGE_BYTES"),
            _DEFAULT_MAX_BYTES,
            "AI_FEATURES_MAX_IMAGE_BYTES",
            notes,
            minimum=1024,
        ),
        max_image_pixels=_as_int(
            get("AI_FEATURES_MAX_IMAGE_PIXELS"),
            _DEFAULT_MAX_PIXELS,
            "AI_FEATURES_MAX_IMAGE_PIXELS",
            notes,
            minimum=1024,
        ),
        timeout_seconds=_as_float(
            get("AI_FEATURES_TIMEOUT_SECONDS"),
            _DEFAULT_TIMEOUT_SECONDS,
            "AI_FEATURES_TIMEOUT_SECONDS",
            notes,
        ),
        local_model=_as_str(get("AI_FEATURES_LOCAL_MODEL"), "u2net"),
        local_model_dir=_as_optional_str(get("AI_FEATURES_LOCAL_MODEL_DIR")),
        cache_local_session=_as_bool(
            get("AI_FEATURES_CACHE_LOCAL_SESSION"), True, "AI_FEATURES_CACHE_LOCAL_SESSION", notes
        ),
        remove_bg_api_key=_as_optional_str(get("REMOVE_BG_API_KEY")),
        remove_bg_endpoint=_as_str(
            get("REMOVE_BG_ENDPOINT"), "https://api.remove.bg/v1.0/removebg"
        ),
        remove_bg_size=_as_choice(
            get("REMOVE_BG_SIZE"),
            "auto",
            frozenset({"auto", "preview", "small", "regular", "medium", "hd", "full", "4k"}),
            "REMOVE_BG_SIZE",
            notes,
        ),
        vectorizer_api_id=_as_optional_str(get("VECTORIZER_AI_API_ID")),
        vectorizer_api_secret=_as_optional_str(get("VECTORIZER_AI_API_SECRET")),
        vectorizer_endpoint=_as_str(
            get("VECTORIZER_AI_ENDPOINT"), "https://vectorizer.ai/api/v1/vectorize"
        ),
        vectorizer_mode=_as_choice(
            get("VECTORIZER_AI_MODE"),
            "test",
            frozenset({"test", "preview", "production"}),
            "VECTORIZER_AI_MODE",
            notes,
        ),
        vector_colormode=_as_choice(
            get("AI_VECTOR_COLORMODE"),
            "color",
            frozenset({"color", "binary"}),
            "AI_VECTOR_COLORMODE",
            notes,
        ),
        vector_filter_speckle=_as_int(
            get("AI_VECTOR_FILTER_SPECKLE"), 4, "AI_VECTOR_FILTER_SPECKLE", notes, minimum=0
        ),
        vector_color_precision=_as_int(
            get("AI_VECTOR_COLOR_PRECISION"), 6, "AI_VECTOR_COLOR_PRECISION", notes, minimum=1
        ),
        market_research_max_results=_as_int(
            get("AI_MARKET_RESEARCH_MAX_RESULTS"),
            _DEFAULT_MARKET_MAX_RESULTS,
            "AI_MARKET_RESEARCH_MAX_RESULTS",
            notes,
            minimum=1,
        ),
        market_research_max_queries=_as_int(
            get("AI_MARKET_RESEARCH_MAX_QUERIES"),
            _DEFAULT_MARKET_MAX_QUERIES,
            "AI_MARKET_RESEARCH_MAX_QUERIES",
            notes,
            minimum=1,
        ),
        market_research_min_interval_seconds=_as_seconds(
            get("AI_MARKET_RESEARCH_MIN_INTERVAL_SECONDS"),
            _DEFAULT_MARKET_MIN_INTERVAL,
            "AI_MARKET_RESEARCH_MIN_INTERVAL_SECONDS",
            notes,
        ),
        market_research_currency=_as_currency(
            get("AI_MARKET_RESEARCH_CURRENCY"), "INR", "AI_MARKET_RESEARCH_CURRENCY", notes
        ),
        market_research_country=_as_str(get("AI_MARKET_RESEARCH_COUNTRY"), "in").lower(),
        market_research_language=_as_str(get("AI_MARKET_RESEARCH_LANGUAGE"), "en").lower(),
        market_research_user_agent=_as_str(
            get("AI_MARKET_RESEARCH_USER_AGENT"), _DEFAULT_MARKET_USER_AGENT
        ),
        market_research_catalogue_path=_as_optional_str(get("AI_MARKET_RESEARCH_CATALOGUE_PATH")),
        serper_api_key=_as_optional_str(get("SERPER_API_KEY")),
        serper_endpoint=_as_str(get("SERPER_ENDPOINT"), "https://google.serper.dev/shopping"),
        serpapi_api_key=_as_optional_str(get("SERPAPI_API_KEY")),
        serpapi_endpoint=_as_str(get("SERPAPI_ENDPOINT"), "https://serpapi.com/search.json"),
        notes=tuple(notes),
    )


def _env_file_candidates() -> tuple[Path, ...]:
    """Where ``backend/.env`` might be, in the order pydantic-settings would find it.

    ``Settings`` uses ``env_file=".env"``, i.e. relative to the working directory, which is
    ``backend/`` for uvicorn, the worker and pytest alike. The second candidate covers a process
    launched from the repository root, where a relative lookup would find nothing.
    """
    package_root = Path(__file__).resolve().parents[2]  # …/backend
    return (Path(".env"), package_root / ".env")


def _read_env_file() -> dict[str, str]:
    """Values for :data:`ENV_VARS` found in .env. Unparseable lines are skipped, never raised on.

    Only our own variables are collected: this is a fallback for one package's flags, not a
    general dotenv loader, and it must not become a second source of truth for DATABASE_URL.
    """
    wanted = set(ENV_VARS)
    found: dict[str, str] = {}
    for candidate in _env_file_candidates():
        try:
            if not candidate.is_file():
                continue
            text = candidate.read_text(encoding="utf-8", errors="replace")
        except OSError:  # unreadable .env is not a reason to fail a probe
            continue
        for line in text.splitlines():
            stripped = line.strip()
            if not stripped or stripped.startswith("#") or "=" not in stripped:
                continue
            key, _, value = stripped.partition("=")
            key = key.strip()
            if key not in wanted or key in found:
                continue
            found[key] = value.strip().strip("\"'")
        if found:
            break
    return found


@lru_cache
def _merged_environment() -> dict[str, str]:
    """Our variables from the real environment, falling back to ``backend/.env``."""
    merged = dict(_read_env_file())
    for name in ENV_VARS:
        value = os.environ.get(name)
        if value is not None:
            merged[name] = value
    return merged


@lru_cache
def get_ai_settings() -> AiFeatureSettings:
    """Cached settings for the running process. Call :func:`reset_ai_settings_cache` after a change.

    Cached because every capability call reads it and because the .env fallback touches the disk;
    a dormant feature has no business stat-ing a file on each request.
    """
    settings = build_settings(_merged_environment())
    for note in settings.notes:
        logger.warning("ai_features configuration: %s", note)
    return settings


def configured_vars() -> tuple[str, ...]:
    """Which of :data:`ENV_VARS` currently have a non-empty value. NAMES ONLY, never values.

    The probe reports this so an operator can see that, say, REMOVE_BG_API_KEY is present without
    the report becoming something that must not be pasted into a ticket.
    """
    environment = _merged_environment()
    return tuple(name for name in ENV_VARS if (environment.get(name) or "").strip())


def reset_ai_settings_cache() -> None:
    """Drop the cache. For tests, and for a future "reload settings" admin action."""
    _merged_environment.cache_clear()
    get_ai_settings.cache_clear()

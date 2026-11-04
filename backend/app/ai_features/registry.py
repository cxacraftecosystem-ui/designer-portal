import importlib
import importlib.util
from typing import Any

from app.ai_features.errors import DependencyMissing, ProviderNotConfigured, UnknownProvider
from app.ai_features.settings import AiFeatureSettings, provider_var
from app.ai_features.types import (
    Capability,
    ProviderDescriptor,
    ProviderReadiness,
    ResourceProfile,
)

_MATTING = frozenset({Capability.BACKGROUND_REMOVAL, Capability.FOREGROUND_SEPARATION})

_SEPARATION_NEEDS_PILLOW: tuple[tuple[Capability, tuple[str, ...]], ...] = (
    (Capability.FOREGROUND_SEPARATION, ("PIL",)),
)


REMBG_LOCAL = ProviderDescriptor(
    id="rembg_local",
    label="rembg (U2Net, on this machine)",
    kind="local",
    capabilities=_MATTING,
    required_modules=("rembg", "onnxruntime", "PIL"),
    required_settings=(),
    implementation="app.ai_features.providers.rembg_local:RembgLocalProvider",
    resources=ResourceProfile(
        basis="MEASURED",
        model_download_mb=176.0,
        peak_ram_mb=1032.0,
        latency="1.3-2.0 s at 1.9 MP, 2.0-2.4 s at 6 MP, plus ~5 s to import and ~4 s to build "
        "the session (MEASURED on a laptop; two burstable vCPU will be slower)",
        money="No per-image charge; you pay in RAM and CPU on your own box.",
        notes=(
            "MEASURED 2026-07-26 in a throwaway venv: rembg with onnxruntime 1.28.0, CPython "
            "3.12.10, Windows, Intel i5-10300H, peak working set of the whole process. u2net on "
            "a 1.9 MP image: 35 MB at rest, 167 MB after 'import rembg' alone, 564 MB once the "
            "session exists, 1,032 MB after one inference; at 6 MP the peak was 1,123 MB. The "
            "lite model (AI_FEATURES_LOCAL_MODEL=u2netp, a 4.7 MB download) still peaked at 696 "
            "MB on the same 1.9 MP image, because the full-resolution RGBA buffers cost more "
            "than the weights do. The production box has 1,024 MB in total and is already "
            "running uvicorn and the Prisma engine, so BOTH models are out of the question "
            "there: this provider needs a bigger instance or a separate worker. The 176 MB "
            "download lands in ~/.u2net on first use (AI_FEATURES_LOCAL_MODEL_DIR moves it) and "
            "took ~17 s here."
        ),
    ),
    summary=(
        "Local U2Net matting through rembg. Free per image, no data leaves the machine, and the "
        "heaviest thing this package can be asked to do."
    ),
)

REMOVE_BG = ProviderDescriptor(
    id="remove_bg",
    label="remove.bg (hosted)",
    kind="hosted",
    capabilities=_MATTING,
    required_modules=("requests",),
    required_settings=("REMOVE_BG_API_KEY",),
    implementation="app.ai_features.providers.remove_bg:RemoveBgProvider",
    extra_modules=_SEPARATION_NEEDS_PILLOW,
    resources=ResourceProfile(
        basis="ESTIMATED",
        model_download_mb=None,
        peak_ram_mb=45.0,
        latency="2-6 s from ap-south-1 for a 3-5 MB photograph, upload included (ESTIMATED)",
        money=(
            "VENDOR-STATED: one credit per full-size image, preview size free. Re-check "
            "remove.bg/pricing before enabling - this was not verified against an account."
        ),
        notes=(
            "RAM is ESTIMATED as roughly three times the image in flight (request buffer, "
            "response buffer, the copy handed back) against the 12 MB input ceiling. Nothing is "
            "downloaded and nothing is cached, so this is the only matting provider that fits on "
            "the t3.micro as it stands. Note that it sends the photograph to a third party: "
            "check that against the consent under which the craft images were collected."
        ),
    ),
    summary=(
        "Hosted cutout. Needs only an API key and the core requests dependency; separation adds "
        "Pillow because the two layers are composited here from the returned alpha."
    ),
)

VTRACER_LOCAL = ProviderDescriptor(
    id="vtracer_local",
    label="VTracer (on this machine)",
    kind="local",
    capabilities=frozenset({Capability.VECTORISATION}),
    required_modules=("vtracer",),
    required_settings=(),
    implementation="app.ai_features.providers.vtracer_local:VtracerLocalProvider",
    resources=ResourceProfile(
        basis="MEASURED",
        model_download_mb=None,
        peak_ram_mb=275.0,
        latency="0.25 s at 0.5 MP, 0.7-1.1 s at 1.9 MP, 2.4-2.8 s at 6 MP (MEASURED)",
        money="No per-image charge. The wheel is a few MB and there is no model to download.",
        notes=(
            "MEASURED 2026-07-26 in a throwaway venv: vtracer 0.6.15, CPython 3.12.10, Windows, "
            "Intel i5-10300H, three runs per size on a synthetic flat-colour motif. Peak working "
            "set of the whole process was 48 MB at 0.5 MP, 111 MB at 1.9 MP and 273 MB at 6 MP, "
            "of which the interpreter baseline was 27/35/59 MB. Importing vtracer itself added "
            "nothing measurable. A photograph is the harder case - more regions, more paths - so "
            "treat these as a floor, and the two burstable vCPU of a t3.micro as slower again. "
            "The 6 MP motif produced a 1.1 MB SVG, which is worth knowing before storing one per "
            "product image."
        ),
    ),
    summary=(
        "Local colour tracing to SVG. Cheap enough to be the sensible default for vectorisation, "
        "unlike local matting."
    ),
)

VECTORIZER_AI = ProviderDescriptor(
    id="vectorizer_ai",
    label="vectorizer.ai (hosted)",
    kind="hosted",
    capabilities=frozenset({Capability.VECTORISATION}),
    required_modules=("requests",),
    required_settings=("VECTORIZER_AI_API_ID", "VECTORIZER_AI_API_SECRET"),
    implementation="app.ai_features.providers.vectorizer_ai:VectorizerAiProvider",
    resources=ResourceProfile(
        basis="ESTIMATED",
        model_download_mb=None,
        peak_ram_mb=45.0,
        latency="3-10 s including upload (ESTIMATED)",
        money=(
            "VENDOR-STATED: mode=test is free and returns a watermarked result, mode=production "
            "spends a credit. This package defaults to test so a mis-wired experiment cannot run "
            "up a bill. Re-check vectorizer.ai/pricing before switching to production."
        ),
        notes=(
            "RAM is ESTIMATED on the same basis as remove.bg. Worth the money only where VTracer's "
            "output is visibly worse - compare the two on one real motif before paying."
        ),
    ),
    summary="Hosted tracing, generally cleaner curves than VTracer on photographic input.",
)

LOCAL_CATALOGUE = ProviderDescriptor(
    id="local_catalogue",
    label="Local price list (on this machine)",
    kind="local",
    capabilities=frozenset({Capability.MARKET_RESEARCH}),
    required_modules=(),
    required_settings=("AI_MARKET_RESEARCH_CATALOGUE_PATH",),
    implementation="app.ai_features.providers.local_catalogue:LocalCatalogueProvider",
    resources=ResourceProfile(
        basis="MEASURED",
        model_download_mb=None,
        peak_ram_mb=28.0,
        latency=(
            "0.18 s over 5,000 rows, 1.7 s over 50,000, 8 s over 120,000 (MEASURED, best of nine, "
            "on a LOADED machine - treat as an upper bound and keep the catalogue to the "
            "categories in scope)"
        ),
        money="No charge and no account. Nothing leaves the machine and no vendor is involved.",
        notes=(
            "MEASURED 2026-08-08 with the repository venv: CPython 3.14.6, Windows 11, Intel "
            "i5-class laptop, synthetic craft catalogues of 5,000 / 50,000 / 120,000 rows "
            "(0.5 / 5.3 / 12.7 MB CSV), three runs each. PEAK WORKING SET WAS FLAT AT 27-28 MB "
            "FOR ALL THREE SIZES - 17 MB of that is the interpreter and ~5 MB is importing this "
            "package - because the scan keeps at most one result budget of listings per overlap "
            "level rather than scoring the whole file and sorting it. The obvious "
            "score-sort-truncate implementation was measured first and took the same process to "
            "57 MB and 15 s on the 120,000-row file; that is why the bucket scan is there. The "
            "timings were taken while the repository's own test suite was running on the same "
            "machine and varied by up to 3x run to run, so they are an upper bound; the memory "
            "figure did not vary at all and is the one that decides whether this fits on the box. "
            "The 16 MB catalogue ceiling was confirmed by pointing it at a 22 MB export, which "
            "was refused from its stat() before a byte was read."
        ),
    ),
    summary=(
        "Comparable products from a CSV or JSONL price list on disk. The offline provider: no "
        "network, no key, no vendor, and the only one that answers in a village with no signal."
    ),
)

SERPER_SHOPPING = ProviderDescriptor(
    id="serper_shopping",
    label="serper.dev Google Shopping (hosted)",
    kind="hosted",
    capabilities=frozenset({Capability.MARKET_RESEARCH}),
    required_modules=("requests",),
    required_settings=("SERPER_API_KEY",),
    implementation="app.ai_features.providers.serper_shopping:SerperShoppingProvider",
    resources=ResourceProfile(
        basis="VENDOR_STATED",
        model_download_mb=None,
        peak_ram_mb=20.0,
        latency="1-3 s per query page from ap-south-1 (ESTIMATED)",
        money=(
            "VENDOR-STATED: serper.dev's own site advertises 2,500 free queries for a new account "
            "(checked 2026-08-08). THIRD-PARTY REPORTED, and deliberately not labelled "
            "vendor-stated because the pricing page could not be read from here: roughly $1.00 "
            "per 1,000 queries at the entry tier falling to about $0.30 per 1,000 at the largest, "
            "one credit per query page at the default depth of ten results and two beyond it, and "
            "credits expiring six months after purchase. RE-CHECK serper.dev BEFORE ENABLING - "
            "none of this was verified against an account, and a brief here costs one credit per "
            "planned query per page."
        ),
        notes=(
            "RAM is ESTIMATED: importing requests and urllib3, plus a JSON response buffered "
            "against the 2 MB ceiling in providers/http.py. Nothing is downloaded and nothing is "
            "cached, so this fits on the t3.micro as it stands. WHAT LEAVES THE BUILDING is the "
            "query only - the designer's keyword plus the craft, category, materials and district "
            "on the brief. No respondent name, no photograph, no captured row. Never run against "
            "the real service: the request shape follows the vendor's published documentation as "
            "of 2026-08 and is covered by stubbed tests."
        ),
    ),
    summary=(
        "Hosted Google Shopping results as structured fields. Cheapest per query of the two "
        "hosted options, and the reason no page ever has to be scraped."
    ),
)

SERPAPI_SHOPPING = ProviderDescriptor(
    id="serpapi_shopping",
    label="SerpApi Google Shopping (hosted)",
    kind="hosted",
    capabilities=frozenset({Capability.MARKET_RESEARCH}),
    required_modules=("requests",),
    required_settings=("SERPAPI_API_KEY",),
    implementation="app.ai_features.providers.serpapi_shopping:SerpApiShoppingProvider",
    resources=ResourceProfile(
        basis="VENDOR_STATED",
        model_download_mb=None,
        peak_ram_mb=20.0,
        latency="2-6 s per query page; SerpApi is documented as slower than serper (ESTIMATED)",
        money=(
            "VENDOR-STATED, read from serpapi.com/pricing on 2026-08-08: 250 searches a month "
            "free; Starter $25/month for 1,000 ($0.025 a search); Developer $75 for 5,000 "
            "($0.015); Production $150 for 15,000 ($0.010); Big Data $275 for 30,000 ($0.009). "
            "One query page is one billable search WHATEVER num is set to, which is why this "
            "provider asks for large pages and few of them. Re-check the page before enabling; "
            "this was not verified against an account."
        ),
        notes=(
            "RAM is ESTIMATED on the same basis as serper. THE API KEY TRAVELS IN THE QUERY "
            "STRING because that is the only place SerpApi accepts it - so it appears in the URL "
            "of an outbound request, and any proxy that logs full URLs will log the key. Worth "
            "knowing before pointing SERPAPI_ENDPOINT at one. Same disclosure as serper "
            "otherwise: the query leaves the building and nothing else. Never run against the "
            "real service."
        ),
    ),
    summary=(
        "The same question through a second company. Exists so that market research is not one "
        "vendor's terms away from disappearing."
    ),
)

TEXT_VERBS = ProviderDescriptor(
    id="text_verbs",
    label="Text verbs (the resolved AI key)",
    kind="hosted",
    capabilities=frozenset({Capability.TRANSCRIPT_INSIGHTS}),
    required_modules=("requests",),
    required_settings=(),
    implementation="",
    resources=ResourceProfile(
        basis="VENDOR_STATED",
        model_download_mb=None,
        peak_ram_mb=None,
        latency="10-60 s per transcript: one summary call, then one call per 12,000-character "
        "translation chunk, run one at a time",
        money="per token on the resolved AI key",
        notes=(
            "The interview text leaves the server for OpenAI, Anthropic or Gemini, whichever key "
            "resolves, and only after the artisan's consent is re-checked at send time. Automatic "
            "runs and the backfill use the deployment's key; a researcher's Regenerate uses their "
            "own key when they have one. No audio is loaded."
        ),
    ),
    summary=(
        "Summary, key quotes and an English translation of a transcript, on the same keys the "
        "text verbs use."
    ),
)

ALL_PROVIDERS: tuple[ProviderDescriptor, ...] = (
    REMBG_LOCAL,
    REMOVE_BG,
    VTRACER_LOCAL,
    VECTORIZER_AI,
    LOCAL_CATALOGUE,
    SERPER_SHOPPING,
    SERPAPI_SHOPPING,
    TEXT_VERBS,
)

_PREFERENCE: dict[Capability, tuple[ProviderDescriptor, ...]] = {
    Capability.BACKGROUND_REMOVAL: (REMOVE_BG, REMBG_LOCAL),
    Capability.FOREGROUND_SEPARATION: (REMOVE_BG, REMBG_LOCAL),
    Capability.VECTORISATION: (VTRACER_LOCAL, VECTORIZER_AI),
    Capability.MARKET_RESEARCH: (LOCAL_CATALOGUE, SERPER_SHOPPING, SERPAPI_SHOPPING),
    Capability.TRANSCRIPT_INSIGHTS: (TEXT_VERBS,),
}


def get_descriptor(provider_id: str) -> ProviderDescriptor:
    for descriptor in ALL_PROVIDERS:
        if descriptor.id == provider_id:
            return descriptor
    known = ", ".join(item.id for item in ALL_PROVIDERS)
    raise UnknownProvider(
        f"no AI feature provider called {provider_id!r}",
        provider=provider_id,
        remediation=f"Use one of: {known}, or 'auto'.",
    )


def providers_for(capability: Capability) -> tuple[ProviderDescriptor, ...]:
    return _PREFERENCE[capability]


def module_available(name: str) -> bool:
    try:
        return importlib.util.find_spec(name) is not None
    except (ImportError, ValueError, AttributeError):
        return False


def readiness(
    descriptor: ProviderDescriptor,
    capability: Capability,
    settings: AiFeatureSettings,
) -> ProviderReadiness:
    if capability not in descriptor.capabilities:
        return ProviderReadiness(
            provider=descriptor.id,
            capability=capability,
            ready=False,
            reason=f"{descriptor.id} does not implement {capability}",
        )
    missing_modules = tuple(
        name for name in descriptor.modules_for(capability) if not module_available(name)
    )
    missing_settings = tuple(
        name for name in descriptor.required_settings if not (settings.value_for(name) or "")
    )
    if missing_modules or missing_settings:
        parts = []
        if missing_modules:
            parts.append("missing packages: " + ", ".join(missing_modules))
        if missing_settings:
            parts.append("unset settings: " + ", ".join(missing_settings))
        return ProviderReadiness(
            provider=descriptor.id,
            capability=capability,
            ready=False,
            missing_modules=missing_modules,
            missing_settings=missing_settings,
            reason="; ".join(parts),
        )
    return ProviderReadiness(
        provider=descriptor.id, capability=capability, ready=True, reason="ready"
    )


def _explain_all(readinesses: tuple[ProviderReadiness, ...]) -> str:
    return " | ".join(f"{item.provider}: {item.reason}" for item in readinesses)


def resolve(
    capability: Capability,
    settings: AiFeatureSettings,
    *,
    requested: str | None = None,
) -> ProviderDescriptor:
    choice = (requested or settings.provider_choice(capability) or "auto").strip().lower()

    if choice != "auto":
        descriptor = get_descriptor(choice)
        if capability not in descriptor.capabilities:
            raise UnknownProvider(
                f"{descriptor.id} does not implement {capability}",
                capability=capability,
                provider=descriptor.id,
                remediation=(
                    "Choose one of: "
                    + ", ".join(item.id for item in providers_for(capability))
                    + ", or 'auto'."
                ),
            )
        state = readiness(descriptor, capability, settings)
        if state.ready:
            return descriptor
        if state.missing_modules:
            raise DependencyMissing(
                f"{descriptor.id} is selected for {capability} but {state.reason}",
                modules=state.missing_modules,
                capability=capability,
                provider=descriptor.id,
                remediation=(
                    "Install the optional extra: pip install -e '.[ai]' (or "
                    "'.[ai-local]' for the local providers)."
                ),
            )
        raise ProviderNotConfigured(
            f"{descriptor.id} is selected for {capability} but {state.reason}",
            missing=state.missing_settings,
            capability=capability,
            provider=descriptor.id,
            remediation="Set " + ", ".join(state.missing_settings) + " in the backend environment.",
        )

    candidates = providers_for(capability)
    states = tuple(readiness(item, capability, settings) for item in candidates)
    for descriptor, state in zip(candidates, states, strict=True):
        if state.ready:
            return descriptor

    only_settings_missing = all(not state.missing_modules for state in states)
    detail = f"no provider for {capability} is usable - {_explain_all(states)}"
    if only_settings_missing:
        raise ProviderNotConfigured(
            detail,
            missing=tuple(dict.fromkeys(sum((s.missing_settings for s in states), ()))),
            capability=capability,
            remediation=(
                "Set the credentials for one of these providers, or point "
                f"{provider_var(capability)} at the one you want and install it."
            ),
        )
    raise DependencyMissing(
        detail,
        modules=tuple(dict.fromkeys(sum((s.missing_modules for s in states), ()))),
        capability=capability,
        remediation=(
            "Install the optional extra for the provider you want: pip install -e "
            "'.[ai]' for the hosted ones, '.[ai-local]' for local inference."
        ),
    )


def load_provider(descriptor: ProviderDescriptor, settings: AiFeatureSettings) -> Any:
    module_path, _, class_name = descriptor.implementation.partition(":")
    try:
        module = importlib.import_module(module_path)
        factory = getattr(module, class_name)
    except ImportError as exc:
        raise DependencyMissing(
            f"{descriptor.id} could not be loaded: {exc}",
            modules=descriptor.required_modules,
            provider=descriptor.id,
            remediation="Install the optional extra, then restart the process.",
        ) from exc
    except AttributeError as exc:
        raise UnknownProvider(
            f"{descriptor.implementation} does not exist",
            provider=descriptor.id,
        ) from exc
    return factory(settings)

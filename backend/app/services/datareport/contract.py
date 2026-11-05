from __future__ import annotations

from collections.abc import AsyncIterator, Awaitable, Callable, Iterable
from dataclasses import dataclass, field
from typing import Any, Literal, Protocol

ColumnKind = Literal[
    "text",
    "longtext",
    "int",
    "number",
    "percent",
    "date",
    "datetime",
    "duration_ms",
    "bytes",
    "link",
    "status",
    "id",
    "bool",
]

Language = Literal["original", "english", "both"]
Locale = Literal["en", "hi"]


@dataclass(frozen=True)
class Column:
    key: str
    label: str
    kind: ColumnKind = "text"
    description: str = ""
    width: int | None = None
    hidden: bool = False
    group: str | None = None
    total: Literal["sum", "count"] | None = None
    source: str | None = None


@dataclass(frozen=True)
class Link:
    text: str
    url: str


@dataclass
class Banner:
    text: str
    url: str | None = None


@dataclass
class ChartSpec:
    title: str
    kind: Literal["column", "bar", "line", "pie", "doughnut"]
    categories: list[str]
    series: list[tuple[str, list[float]]]


@dataclass
class Sheet:
    key: str
    name: str
    columns: list[Column]
    rows: Iterable[dict[str, Any]] | AsyncIterator[dict[str, Any]]
    color: str = "762CB1"
    banners: list[Banner] = field(default_factory=list)
    note: str | None = None
    table: bool = True
    sparkline_columns: list[str] = field(default_factory=list)
    databar_columns: list[str] = field(default_factory=list)
    row_pairs: bool = False
    group: str | None = None


@dataclass
class ReportOptions:
    sheets: list[str] | None = None
    answers: Language = "both"
    rows: Literal["all", "filtered"] = "all"
    links: bool = True
    thumbnails: bool = False
    locale: Locale = "en"
    extras: dict[str, bool] = field(default_factory=dict)
    q: str | None = None
    name: str | None = None


class LinkBuilder(Protocol):
    def download(self, media_id: str) -> str: ...

    def open_media(self, media_id: str) -> str: ...

    def open_record(self, kind: str, record_id: str) -> str: ...

    def play_from(self, media_id: str, start_ms: int) -> str: ...

    def transcript_file(self, media_id: str, fmt: Literal["docx", "srt"]) -> str: ...

    def section(self, interview_id: str, section_code: str) -> str: ...

    def data_folder(self, path: str) -> str: ...

    async def bundle(self, label: str, media_ids: list[str]) -> str: ...


class Translator(Protocol):
    def __call__(self, key: str, default: str) -> str: ...


@dataclass
class ReportContext:
    portal: Literal["designer", "fieldRepository"]
    user: Any
    scope: Any
    path: str
    folder_name: str
    material: dict[str, Any]
    options: ReportOptions
    links: LinkBuilder
    t: Translator
    data_version: str
    generated_at: str
    cover_charts: list[ChartSpec] = field(default_factory=list)
    cover_kpis: list[tuple[str, str, str]] = field(default_factory=list)
    cache: Callable[[str, Callable[[], Awaitable[Any]]], Awaitable[Any]] | None = None


class SheetPlugin(Protocol):
    key: str
    title: str
    order: int
    option: str | None
    portals: tuple[str, ...]

    async def build(self, ctx: ReportContext) -> list[Sheet]: ...


_PLUGINS: dict[str, SheetPlugin] = {}


def register(plugin: SheetPlugin) -> SheetPlugin:
    if plugin.key in _PLUGINS and _PLUGINS[plugin.key] is not plugin:
        raise ValueError(f"two report sheets share the key {plugin.key!r}")
    _PLUGINS[plugin.key] = plugin
    return plugin


def plugins(portal: str) -> list[SheetPlugin]:
    from app.services.datareport import sheets as _sheets

    _sheets.load()
    return sorted((p for p in _PLUGINS.values() if portal in p.portals), key=lambda p: (p.order, p.key))


def optional_keys(portal: str) -> list[tuple[str, str]]:
    return [(p.option, p.title) for p in plugins(portal) if p.option]

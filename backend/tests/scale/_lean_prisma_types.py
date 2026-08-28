"""Import the generated Prisma client without paying for its 19 MB of TypedDicts.

WHY THIS EXISTS, AND WHY IT IS A BENCHMARK HARNESS FILE RATHER THAN ANYTHING THE APP IMPORTS.
``prisma/types.py`` in this project's generated client is 19.3 MB and declares 52,528 classes --
``recursive_type_depth = 5`` in ``prisma/schema.prisma`` is what multiplies them out. Every one of
them is a ``TypedDict`` that exists PURELY for static checking: nothing in prisma-client-py's
runtime reads them, because a Prisma query is assembled from plain dicts.

Measured on this machine (2026-08-27, Python 3.14.7, 15.7 GB RAM with 3.6 GB free):

  * ``import prisma`` on Python 3.14 ran for 839 SECONDS and then died with ``MemoryError`` inside
    ``annotationlib.call_annotate_function`` -- PEP 649's deferred-annotation machinery builds a
    stringifier namespace per annotated class, and 52,528 of them will not fit.
  * With the stub below installed first, the same import completes in a few seconds.

Production is unaffected by the crash: the Dockerfile pins ``PYTHON_VERSION=3.12`` and CI runs
3.12, where PEP 649 is not in play. What production DOES still pay is the import itself, which is
part of every cold start and every systemd restart. That is worth knowing and is reported as a
finding, not fixed here.

**NOTHING IN app/ MAY IMPORT THIS.** It is a measurement tool. Type information is what tells the
next person editing a query that they have spelled a field wrong, and swapping it out inside the
application would trade a real safety net for a startup second. It is imported by the benchmark
harness in this directory, before ``app`` is imported, and nowhere else.
"""

from __future__ import annotations

import importlib.abc
import importlib.machinery
import importlib.util
import sys
from types import ModuleType

_STUBBED = "prisma.types"


class _AnyDictModule(ModuleType):
    """A module where every attribute is ``dict``.

    ``from .types import FooInclude`` and ``types.FooWhereInput`` both resolve through PEP 562's
    module-level ``__getattr__``, so every one of the 52,528 names answers without the file that
    declares them ever being read. ``dict`` rather than ``Any`` because that is what these aliases
    actually are at runtime -- a ``TypedDict`` IS a ``dict`` once the type checker has gone home.
    """

    def __getattr__(self, name: str) -> object:
        if name.startswith("__") and name.endswith("__"):
            raise AttributeError(name)
        return dict


class _StubLoader(importlib.abc.Loader):
    def create_module(self, spec: importlib.machinery.ModuleSpec) -> ModuleType:
        return _AnyDictModule(spec.name)

    def exec_module(self, module: ModuleType) -> None:
        return None


class _StubFinder(importlib.abc.MetaPathFinder):
    """Answers for ``prisma.types`` alone and defers to the normal machinery for everything else.

    A ``sys.meta_path`` finder rather than a pre-seeded ``sys.modules`` entry, because the import
    system is then the thing that sets ``prisma.types`` as an attribute of the ``prisma`` package.
    Pre-seeding leaves that attribute unset, and ``from . import types`` inside ``prisma/client.py``
    would half-work in a way that depends on CPython's fallback order.
    """

    def find_spec(
        self, fullname: str, path: object = None, target: object = None
    ) -> importlib.machinery.ModuleSpec | None:
        if fullname != _STUBBED:
            return None
        return importlib.util.spec_from_loader(fullname, _StubLoader(), is_package=False)


def install() -> None:
    """Put the stub in front of the import system. Call BEFORE anything imports ``prisma``."""
    if _STUBBED in sys.modules:
        raise RuntimeError(
            "prisma.types is already imported -- install() has to run before the first "
            "`import prisma` (or anything that imports app.core.db), or it buys nothing."
        )
    if not any(isinstance(finder, _StubFinder) for finder in sys.meta_path):
        sys.meta_path.insert(0, _StubFinder())

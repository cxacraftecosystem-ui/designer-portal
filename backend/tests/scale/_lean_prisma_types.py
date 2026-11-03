from __future__ import annotations

import importlib.abc
import importlib.machinery
import importlib.util
import sys
from types import ModuleType

_STUBBED = "prisma.types"


class _AnyDictModule(ModuleType):
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
    def find_spec(
        self, fullname: str, path: object = None, target: object = None
    ) -> importlib.machinery.ModuleSpec | None:
        if fullname != _STUBBED:
            return None
        return importlib.util.spec_from_loader(fullname, _StubLoader(), is_package=False)


def install() -> None:
    if _STUBBED in sys.modules:
        raise RuntimeError(
            "prisma.types is already imported -- install() has to run before the first "
            "`import prisma` (or anything that imports app.core.db), or it buys nothing."
        )
    if not any(isinstance(finder, _StubFinder) for finder in sys.meta_path):
        sys.meta_path.insert(0, _StubFinder())

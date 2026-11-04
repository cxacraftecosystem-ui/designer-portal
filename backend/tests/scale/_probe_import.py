import pathlib
import sys
import time

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))

import _lean_prisma_types

_lean_prisma_types.install()

_t0 = time.perf_counter()
import prisma  # noqa: E402  (the import is the measurement)

_t1 = time.perf_counter()
print(f"import prisma with stub: {_t1 - _t0:.2f}s", flush=True)
print("prisma.types is stub:", prisma.types.AnythingAtAll is dict, flush=True)

_t2 = time.perf_counter()
from app.main import create_app  # noqa: E402, F401  (the import is the measurement)

_t3 = time.perf_counter()
print(f"import app.main: {_t3 - _t2:.2f}s", flush=True)

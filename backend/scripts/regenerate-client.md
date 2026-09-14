# Regenerating the Prisma client on Windows

`prisma generate` and `prisma migrate` **cannot run on Windows in this repository** without the two
workarounds below. Both failures are silent about their real cause and both cost a day the first
time. Diagnosed 2026-09-14.

## Failure 1 — every prisma command dies before it starts

```
File ".venv\Lib\site-packages\prisma\_config.py", line 111, in load
    config = tomlkit.loads(path.read_text()).get('tool', {}).get('prisma', {})
UnicodeDecodeError: 'charmap' codec can't decode byte 0x90 in position 17363
```

`prisma/_config.py` calls `Path.read_text()` with **no `encoding=`**, so Python uses the locale
codec — `cp1252` on this machine. It is reading `backend/pyproject.toml`, which is UTF-8 and full of
box-drawing and typographic characters in the long comment blocks. Byte 17363 is one of them.

This hits `generate`, `migrate status`, `migrate deploy` — everything. It is a bug in
prisma-client-py, not in this repository, and editing `pyproject.toml` to appease it would mean
stripping the comments that make it worth reading.

**Fix: `PYTHONUTF8=1`.** Python then decodes as UTF-8 regardless of locale.

```bash
PYTHONUTF8=1 ./.venv/Scripts/python.exe -m prisma migrate status
PYTHONUTF8=1 ./.venv/Scripts/python.exe -m prisma migrate deploy
```

Both work. `migrate deploy` is the right command here — it applies what is on disk and never
invents a migration, which is what you want against a database somebody else may also be using.

## Failure 2 — generate still cannot find its own generator

```
Error: spawn prisma-client-py ENOENT
```

`.venv/Scripts/prisma-client-py.exe` **exists** and both PowerShell and Python resolve it. The Node
CLI cannot: it spawns the provider by the bare name `prisma-client-py` without `shell: true`, and
that path does not apply `PATHEXT`, so the `.exe` is never tried. Putting the venv on `PATH` does not
help, because the name is what is wrong, not the directory.

The two fixes the internet offers are **both refused here**: pointing `generator.provider` at an
absolute `…\prisma-client-py.exe` puts one machine's layout into a tracked file, and dropping an
extensionless shim on `PATH` is a machine-wide change to fix one repository.

**Fix: generate in Linux, where the spawn works, and copy the result in.** The generated client is
pure Python and platform-independent — the query engine binary is downloaded separately at runtime
into `~/.cache/prisma-python` and is *not* part of what is copied.

```bash
OUT=/tmp/prismagen && rm -rf "$OUT" && mkdir -p "$OUT"
docker run --rm \
  -v "C:/dev/IIT/designer-portal/backend/prisma:/work/prisma:ro" \
  -v "$OUT:/out" \
  -e DATABASE_URL="postgresql://u:p@127.0.0.1:5432/db" \
  python:3.14-slim bash -lc '
    apt-get update -qq && apt-get install -y -qq nodejs npm   # prisma bootstraps a nodeenv and needs npm
    pip install --quiet prisma==0.15.0                        # MUST match the version in the venv
    SP=$(python -c "import prisma,os;print(os.path.dirname(prisma.__file__))")
    cp -r "$SP" /tmp/before
    cd /work && prisma generate
    cd "$SP" && for f in $(find . -name "*.py" | sed "s|^\./||"); do
      if [ ! -f "/tmp/before/$f" ] || ! cmp -s "$f" "/tmp/before/$f"; then
        mkdir -p "/out/$(dirname $f)"; cp "$f" "/out/$f"
      fi
    done'
```

`DATABASE_URL` is a dummy on purpose — `generate` reads the schema and never connects.

Then copy the twelve generated files into the venv:

    actions.py  bases.py  client.py  enums.py  http.py  metadata.py  models.py
    partials.py  types.py  engine/abstract.py  engine/http.py  engine/query.py

### AND THE THIRTEENTH FILE, WHICH IS NOT A `.py` AND IS THE ONE THAT BITES

`site-packages/prisma/schema.prisma`. The generator copies the schema in beside the generated
modules, and **the query engine loads that copy, not `backend/prisma/schema.prisma`**. Copy it too:

```bash
cp backend/prisma/schema.prisma backend/.venv/Lib/site-packages/prisma/schema.prisma
```

Miss it and everything imports cleanly, the client advertises the new models, and then every query
fails at runtime with a message that names a field rather than a schema:

```
prisma.errors.FieldNotFoundError: Could not find field at `createOneUser.data.role`
```

Which reaches the browser as **"Something went wrong on the server. The error has been logged."** —
a 500 with no hint that a stale schema is the cause. That is the whole failure this file exists to
stop somebody re-deriving.

Finally, clear the stale bytecode, or the old modules keep being imported:

```bash
find backend/.venv/Lib/site-packages/prisma -name __pycache__ -type d -exec rm -rf {} +
```

## Verifying

```bash
PYTHONUTF8=1 ./.venv/Scripts/python.exe -c "
from prisma import Prisma; p = Prisma()
print('sanctionorder', hasattr(p, 'sanctionorder'))
from prisma import enums; print(enums.Role.MINISTRY_ADMIN)"

PYTHONUTF8=1 PYTHONPATH=. ./.venv/Scripts/python.exe scripts/seed_test_accounts.py
```

The seed script is the sharpest check available: it writes one account per rung of the ladder, so a
client that disagrees with the database about the `Role` enum fails on the first insert rather than
on whichever screen somebody opens next.

## None of this applies to CI or to the servers

`deploy-backend.yml` runs `prisma generate` and `prisma migrate deploy` on Linux, where neither
failure exists. Production has never been affected by either of these, and was not affected by the
outage that produced this file — the drift was local only.

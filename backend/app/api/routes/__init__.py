# Deliberately empty of code: THE ROUTERS ARE NOT MOUNTED HERE.
#
# Every module in this package declares its own ``APIRouter``, and they are imported and mounted in
# ``app/api/router.py`` — the import tuple at the top of that file and the ``include_router`` calls
# below it, several of which carry an ordering argument in a comment. Adding a registration line to
# this file would do nothing at all: nothing imports ``app.api.routes`` for its side effects, so the
# route would simply not exist and the failure would look like a 404 on a path that is plainly
# declared two directories away. This note is here because that mistake has already been proposed
# once, on the reasonable-sounding grounds that a package ``__init__`` is where registration usually
# lives.

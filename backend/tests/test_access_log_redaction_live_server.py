"""THE REDACTED ACCESS LINE, AS A REAL UVICORN SERVER WRITES IT — NOT AS A TEST REBUILDS IT.

``app.main.AccessLogRedaction`` keys on the exact call uvicorn makes for its access line: five
arguments, the path third. The tests beside it in test_auth_identity_and_password_links.py build that
record with uvicorn's own helpers and make that call themselves — which is exactly what they cannot
notice changing. If a uvicorn release adds an argument, reorders them or logs through ``extra=``, the
filter stops matching, passes the line through untouched, and the set-password token is back in the
service's journal while every one of those tests still passes.

So this module starts uvicorn — the installed version, on both HTTP implementations production can
pick — with the logging production gets (uvicorn's own LOGGING_CONFIG, applied before the
application installs its filter, as ``uvicorn app.main:app`` does), sends a link check through a real
socket, and reads the line uvicorn wrote. Needs no database: the ASGI app here answers every request
with 200 and is not the product's.
"""

import io
import logging
import socket
import threading
import time
import urllib.request

import pytest
import uvicorn
from uvicorn.config import LOGGING_CONFIG
from uvicorn.logging import AccessFormatter

from app.main import AccessLogRedaction, install_access_log_redaction

LINK_TOKEN = "Live-Uvicorn-Link-Token-0451"


async def _answer_everything(scope, receive, send):
    if scope["type"] == "lifespan":
        while True:
            message = await receive()
            if message["type"] == "lifespan.startup":
                await send({"type": "lifespan.startup.complete"})
            elif message["type"] == "lifespan.shutdown":
                await send({"type": "lifespan.shutdown.complete"})
                return
    await send({"type": "http.response.start", "status": 200, "headers": [(b"content-type", b"text/plain")]})
    await send({"type": "http.response.body", "body": b"ok"})


@pytest.fixture
def uvicorn_logging_restored():
    """uvicorn's dictConfig rewrites three loggers' handlers, levels and propagation for the whole
    process; put them back so no other test inherits a server's logging."""
    names = ("uvicorn", "uvicorn.error", "uvicorn.access")
    saved = {
        name: (
            list(logging.getLogger(name).handlers),
            logging.getLogger(name).level,
            logging.getLogger(name).propagate,
            list(logging.getLogger(name).filters),
        )
        for name in names
    }
    yield
    for name, (handlers, level, propagate, filters) in saved.items():
        logger = logging.getLogger(name)
        logger.handlers[:] = handlers
        logger.setLevel(level)
        logger.propagate = propagate
        logger.filters[:] = filters


@pytest.mark.parametrize("http", ["h11", "httptools"])
def test_a_running_uvicorn_writes_the_link_check_with_its_token_redacted(http, uvicorn_logging_restored):
    if http == "httptools":
        pytest.importorskip("httptools")

    listener = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    listener.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    listener.bind(("127.0.0.1", 0))
    port = listener.getsockname()[1]

    # PRODUCTION'S ORDER: building the Config applies uvicorn's LOGGING_CONFIG (dictConfig replaces
    # the access logger's handlers and keeps its filters), and only then does the application's
    # import put the filter on the logger. app.main was imported above, so this re-asserts it the way
    # that import does — idempotently.
    config = uvicorn.Config(
        _answer_everything,
        http=http,
        lifespan="on",
        access_log=True,
        log_config=LOGGING_CONFIG,
        log_level="info",
    )
    install_access_log_redaction()
    access = logging.getLogger("uvicorn.access")
    assert any(isinstance(each, AccessLogRedaction) for each in access.filters)

    written = io.StringIO()
    handler = logging.StreamHandler(written)
    handler.setFormatter(AccessFormatter(LOGGING_CONFIG["formatters"]["access"]["fmt"], use_colors=False))
    access.addHandler(handler)

    server = uvicorn.Server(config)
    thread = threading.Thread(target=server.run, kwargs={"sockets": [listener]}, daemon=True)
    thread.start()
    try:
        deadline = time.monotonic() + 30
        while not server.started:
            assert time.monotonic() < deadline, "uvicorn did not start within 30 s"
            assert thread.is_alive(), "uvicorn exited before it started"
            time.sleep(0.05)
        url = f"http://127.0.0.1:{port}/api/auth/set-password?token={LINK_TOKEN}&page=2"
        with urllib.request.urlopen(url, timeout=10) as response:
            assert response.status == 200
    finally:
        server.should_exit = True
        thread.join(timeout=15)
        listener.close()

    line = written.getvalue()
    assert LINK_TOKEN not in line
    assert '"GET /api/auth/set-password?token=[redacted]&page=2 HTTP/1.1" 200' in line, line

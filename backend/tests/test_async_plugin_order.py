"""AN ANYIO TEST AND ITS ASYNC FIXTURES RUN ON ONE EVENT LOOP — THE PROPERTY, NOT THE ORDER.

``backend/pyproject.toml`` loads anyio's pytest plugin before pytest-asyncio's, and
``conftest.pytest_configure`` refuses the other order. Both of those are about ORDER, which is the
cause; this module checks the EFFECT the database-backed modules depend on: a connection an async
fixture opens is usable from the test that asked for it. When it was not, 37 integration tests
failed with "... is bound to a different event loop" on every CI run whose site-packages happened to
list pytest-asyncio first, on Python 3.12 and 3.14 alike, while every laptop stayed green.

Needs no database. An ``asyncio.Event`` stands in for Prisma's httpx pool: it binds to the loop
that first waits on it, exactly the object the integration traceback named.
"""

import asyncio

import pytest

pytestmark = pytest.mark.anyio


@pytest.fixture
def anyio_backend():
    return "asyncio"


@pytest.fixture
async def opened_in_fixture():
    """What ``db.connect()`` leaves behind: state bound to the loop the fixture ran on."""
    event = asyncio.Event()
    event.set()
    await event.wait()
    yield asyncio.get_running_loop(), event


@pytest.fixture
async def layered(opened_in_fixture):
    """The shape of test_save_stage_resubmission.py: an async fixture built on another one."""
    yield opened_in_fixture


async def test_the_fixture_and_the_test_share_one_loop(opened_in_fixture):
    loop, event = opened_in_fixture
    assert loop is asyncio.get_running_loop(), (
        "the async fixture ran on a different event loop from its test; see the note on `addopts` "
        "in backend/pyproject.toml"
    )
    event.clear()
    event.set()
    await event.wait()


async def test_a_fixture_built_on_a_fixture_shares_the_loop_too(layered):
    loop, event = layered
    assert loop is asyncio.get_running_loop()
    await event.wait()


def test_the_plugins_are_registered_anyio_first(pytestconfig):
    names = [name for name, _plugin in pytestconfig.pluginmanager.list_name_plugin()]
    assert "anyio" in names
    assert "asyncio" in names
    assert names.index("anyio") < names.index("asyncio")

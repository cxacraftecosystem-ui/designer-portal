"""AN ANYIO TEST AND ITS ASYNC FIXTURES RUN ON ONE EVENT LOOP — THE PROPERTY, NOT THE ORDER.

``backend/pyproject.toml`` loads anyio's pytest plugin before pytest-asyncio's, and
``conftest.pytest_configure`` refuses the other order. Both of those are about ORDER, which is the
cause; this module checks the EFFECT every module that opens ``db`` in an async fixture depends on:
what the fixture opened is usable from the test that asked for it. When it was not, 37 integration
tests failed with "... is bound to a different event loop" on every CI run whose site-packages
happened to list pytest-asyncio first, whatever the interpreter, while every laptop stayed green.
Those three modules have since moved onto ``asyncio.run``; forty-nine others still open ``db`` in an
async fixture.

Needs no database. An ``asyncio.Event`` stands in for Prisma's httpx pool, the object the
integration traceback named: once a wait has had to suspend on it, it belongs to that loop, and a
wait from any other loop raises that same error. A wait on an event that is already set returns at
once and binds nothing, so every wait below is made to suspend.
"""

import asyncio

import pytest

pytestmark = pytest.mark.anyio


@pytest.fixture
def anyio_backend():
    return "asyncio"


async def _wait_on(event: asyncio.Event) -> None:
    """Wait on ``event`` in a way that has to suspend, which is what ties it to the running loop."""
    event.clear()
    asyncio.get_running_loop().call_soon(event.set)
    await event.wait()


@pytest.fixture
async def opened_in_fixture():
    """What ``db.connect()`` leaves behind: state bound to the loop the fixture ran on."""
    event = asyncio.Event()
    await _wait_on(event)
    yield asyncio.get_running_loop(), event


@pytest.fixture
async def layered(opened_in_fixture):
    """An async fixture built on another: test_save_stage_resubmission.py's shape until PR #24."""
    yield opened_in_fixture


async def test_the_fixture_and_the_test_share_one_loop(opened_in_fixture):
    loop, event = opened_in_fixture
    assert loop is asyncio.get_running_loop(), (
        "the async fixture ran on a different event loop from its test; see the note on `addopts` "
        "in backend/pyproject.toml"
    )
    await _wait_on(event)


async def test_a_fixture_built_on_a_fixture_shares_the_loop_too(layered):
    loop, event = layered
    assert loop is asyncio.get_running_loop()
    await _wait_on(event)


def test_the_plugins_are_registered_anyio_first(pytestconfig):
    names = [name for name, _plugin in pytestconfig.pluginmanager.list_name_plugin()]
    assert "anyio" in names
    assert "asyncio" in names
    assert names.index("anyio") < names.index("asyncio")

import asyncio

import pytest

pytestmark = pytest.mark.anyio


@pytest.fixture
def anyio_backend():
    return "asyncio"


async def _wait_on(event: asyncio.Event) -> None:
    event.clear()
    asyncio.get_running_loop().call_soon(event.set)
    await event.wait()


@pytest.fixture
async def opened_in_fixture():
    event = asyncio.Event()
    await _wait_on(event)
    yield asyncio.get_running_loop(), event


@pytest.fixture
async def layered(opened_in_fixture):
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

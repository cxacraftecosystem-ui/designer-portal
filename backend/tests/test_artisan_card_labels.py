"""Where a card event says it came from, and what a printed card file is called."""

import re
from types import SimpleNamespace

from app.api.routes import artisan_cards as card_routes
from app.api.routes import artisans as artisan_routes


def _request(headers: dict[str, str]):
    return SimpleNamespace(headers={k.lower(): v for k, v in headers.items()})


def test_the_app_labels_its_card_events_android_and_everything_else_is_web():
    assert artisan_routes._card_source(_request({"X-Client-App": "android"})) == "ANDROID"
    assert artisan_routes._card_source(_request({"X-Client-App": " Android "})) == "ANDROID"
    assert artisan_routes._card_source(_request({})) == "WEB"
    assert artisan_routes._card_source(_request({"X-Client-App": "okhttp/5"})) == "WEB"
    assert artisan_routes._card_source(None) == "WEB"


def _face(name: str, craft: str | None = "Hand block printing", cluster: str | None = "Bagru"):
    return SimpleNamespace(name=name, craft=craft, cluster=cluster)


def test_card_files_follow_the_naming_convention():
    one = card_routes._card_file_name([_face("Sita Devi")])
    assert re.fullmatch(
        r"DesignWorkshop_Artisan_\d{4}-\d{2}-\d{2}_Bagru_Hand-Block-Printing_Sita-Devi_Artisan-Card\.pdf", one
    )
    many = card_routes._card_file_name([_face("A"), _face("B")])
    assert re.fullmatch(r"DesignWorkshop_Artisan_\d{4}-\d{2}-\d{2}_Artisan-Cards\.pdf", many)


def test_fastapi_hands_the_request_to_the_list_and_the_create():
    from fastapi.dependencies.utils import get_dependant

    for route in (artisan_routes.list_artisans, artisan_routes.create_artisan):
        assert get_dependant(path="/artisans", call=route).request_param_name == "request"

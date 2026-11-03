from __future__ import annotations

import json
import pathlib
import re

import pytest

from tests.test_questionnaire_form_contract import (
    _balanced,
    _strip_kotlin_comments,
    _strip_ts_comments,
)

_ROOT = pathlib.Path(__file__).resolve().parents[2]
CONTRACT_PATH = _ROOT / "shared" / "record-save-contract.json"
ANDROID_MAIN = (
    _ROOT
    / "android"
    / "app"
    / "src"
    / "main"
    / "java"
    / "com"
    / "designprototype"
    / "workshop"
    / "MainActivity.kt"
)


def contract() -> dict:
    return json.loads(CONTRACT_PATH.read_text(encoding="utf-8"))


def read(rel: str) -> str:
    path = _ROOT / rel
    assert path.exists(), (
        f"{CONTRACT_PATH.name} names {rel}, which is not in the tree. Every path in the contract is "
        "a file this test opens; a renamed or deleted file is a change to the contract."
    )
    source = path.read_text(encoding="utf-8")
    if path.suffix in (".ts", ".tsx"):
        return _strip_ts_comments(source)
    if path.suffix == ".kt":
        return _strip_kotlin_comments(source)
    return source


def line_of(source: str, offset: int) -> int:
    return source.count("\n", 0, offset) + 1



def kotlin_fun_body(source: str, name: str) -> tuple[str, int]:
    found = re.search(rf"^(?:private |internal )?fun {name}\(", source, re.MULTILINE)
    assert found, (
        f"`fun {name}(` is no longer declared in the handset's source. The contract names it as a "
        "record form; if it was renamed or moved, the contract moves with it."
    )
    params_at = source.index("(", found.start())
    params = _balanced(source, params_at, "(", ")")
    body_at = source.index("{", params_at + len(params))
    return _balanced(source, body_at, "{", "}"), body_at


def kotlin_blocks(source: str, anchor: str) -> list[str]:
    blocks: list[str] = []
    at = source.find(anchor)
    while at >= 0:
        blocks.append(_balanced(source, source.index("{", at), "{", "}"))
        at = source.find(anchor, at + len(anchor))
    assert blocks, (
        f"the contract names the anchor {anchor!r}, which is no longer in the handset's source."
    )
    return blocks


def calls(body: str, name: str) -> list[int]:
    return [m.start() for m in re.finditer(rf"(?<![A-Za-z0-9_]){name}\s*\(", body)]



def android_records(spec: dict) -> list[dict]:
    return [record for record in spec["records"] if not record["android"].get("retired")]


def web_records(spec: dict) -> list[dict]:
    return [record for record in spec["records"] if record.get("web")]


def test_the_contract_and_every_file_it_names_still_parse_to_something():
    spec = contract()
    assert spec["contract"] == "record-save"
    assert spec["records"], "the contract declares no records at all"

    for record in web_records(spec):
        web = read(record["web"]["form"])
        assert len(web) > 2000, f"{record['web']['form']} stripped to {len(web)} characters"

    android = read(str(ANDROID_MAIN.relative_to(_ROOT)).replace("\\", "/"))
    for record in android_records(spec):
        body, _ = kotlin_fun_body(android, record["android"]["form"])
        assert len(body) > 2000, (
            f"the handset's `{record['android']['form']}` parsed to {len(body)} characters, which "
            "is not a record form. Everything below scans that region; until the parser is fixed, "
            "the assertions pass by finding nothing."
        )



def test_each_web_record_form_has_exactly_one_write_and_it_is_the_declared_endpoint():
    spec = contract()
    call = spec["savePath"]["web"]["call"]
    for record in web_records(spec):
        rel = record["web"]["form"]
        source = read(rel)
        sites = [m.start() for m in re.finditer(re.escape(call), source)]
        assert len(sites) == 1, (
            f"{rel} makes {len(sites)} `{call}` calls and the contract declares one. Two writes in "
            "one record form is how the standalone door and the integrated door come to disagree: "
            "they are the same component, so a second write is reachable from both and matches "
            "neither."
        )
        block = _balanced(source, source.index("{", sites[0]), "{", "}")
        expected = record["web"]["endpoint"]
        assert expected in block, (
            f"{rel}:{line_of(source, sites[0])} no longer writes to the endpoint the contract "
            f"declares.\n    contract: {expected}\n    A record created from a design-workshop "
            "stage and one created from /tools/new must reach the same route; the contract is "
            "where that is decided, not the form."
        )


def test_no_web_record_form_reaches_the_api_any_other_way():
    spec = contract()
    for record in web_records(spec):
        rel = record["web"]["form"]
        source = read(rel)
        for forbidden in spec["savePath"]["web"]["forbiddenInForms"]:
            assert forbidden not in source, (
                f"{rel} calls {forbidden}…) directly. Every record write goes through "
                f"`{spec['savePath']['web']['function']}`, which is what makes the save identical "
                "from both doors and what makes it survive a device with no signal."
            )


def test_each_android_record_form_saves_through_the_repository_and_the_one_queue():
    spec = contract()
    android = read(str(ANDROID_MAIN.relative_to(_ROOT)).replace("\\", "/"))
    spellings = spec["savePath"]["android"]["queueFunctions"]
    for record in android_records(spec):
        name = record["android"]["form"]
        body, at = kotlin_fun_body(android, name)

        assert record["android"]["createCall"] in body, (
            f"the handset's `{name}` (MainActivity.kt:{line_of(android, at)}) no longer calls "
            f"`{record['android']['createCall']}`. The contract declares it as this record's one "
            "create."
        )
        if record["android"]["createVia"] == "onSubmit":
            supplied = record["android"]["mountSupplies"]
            assert supplied in android, (
                f"no mount of `{name}` supplies `{supplied}`. Its create is injected at the mount, "
                "so a mount that supplies something else is a second save path in the one shape "
                "this contract cannot see from inside the form."
            )
            refuses = record["android"].get("mountRefuses")
            if refuses:
                assert refuses in android, (
                    f"the mount of `{name}` that opens an existing record no longer refuses to "
                    f"create: `{refuses}` is gone. A correctly-typed wrong verb there is one "
                    "refactor away from filing a second record for an interview that already has "
                    "one — a 409 where the unique index bites, and two half-answered sittings "
                    "where it does not."
                )

        declared = record["android"]["queueVia"]
        assert declared in spellings, (
            f"{CONTRACT_PATH.name} says `{name}` queues via `{declared}`, which is not one of "
            f"{spellings}."
        )
        assert declared in body, (
            f"the handset's `{name}` no longer reaches `{declared}`. A record form that cannot "
            "queue is a record form that loses a day's fieldwork on a device with no signal."
        )
        for other in spellings:
            if other != declared:
                assert other not in body, (
                    f"the handset's `{name}` now reaches BOTH `{declared}` and `{other}`. Two ways "
                    "into the outbox from one form is two shapes of queued record."
                )


def test_no_record_creating_route_exists_under_design_workshops():
    source = (_ROOT / "backend" / "app" / "api" / "routes" / "design_workshops.py").read_text(
        encoding="utf-8"
    )
    nouns = {"artisans", "products", "tools", "processes", "interviews", "records"}
    for match in re.finditer(r'@router\.(post|patch|put)\("([^"]*)"', source):
        segments = [part for part in match.group(2).split("/") if part and not part.startswith("{")]
        clash = nouns.intersection(segments)
        assert not clash, (
            f'design_workshops.py declares `@router.{match.group(1)}("{match.group(2)}")`, which '
            f"looks like a second way to write a record ({', '.join(sorted(clash))}). R7 is one "
            "save path: a record made inside a design workshop goes to the same route as one made "
            "from its own page. If this route is genuinely something else, name it something that "
            "is not a record noun and say so here."
        )



def test_the_web_integrated_hosts_mount_the_real_record_forms():
    spec = contract()
    integrated = spec["theTwoDoors"]["web"]["integrated"]
    dialog = read(integrated["files"][0])
    for record in web_records(spec):
        if record["key"] == "interview":
            continue
        component = pathlib.PurePath(record["web"]["form"]).stem
        assert (
            f'from "@/components/forms/{component}"' in dialog
            or f'import("@/components/forms/{component}")' in dialog
        ), (
            f"{integrated['files'][0]} no longer imports {component} from components/forms. The "
            "integrated door must mount the REAL record form; a host-local copy is a second answer "
            "to what this record is."
        )
        assert f"<{component}" in dialog, (
            f"{integrated['files'][0]} imports {component} and never mounts it."
        )

    embed = read(integrated["files"][1])
    mount = integrated["mountsThrough"]
    assert f"<{mount}" in embed, (
        f"{integrated['files'][1]} no longer renders <{mount}/>. Two hosts sharing one mount is "
        "what stops the four host callbacks being invented a third time."
    )


def test_the_android_integrated_dialog_mounts_the_same_composables_as_the_create_screen():
    spec = contract()
    android = read(str(ANDROID_MAIN.relative_to(_ROOT)).replace("\\", "/"))
    dialogs = kotlin_blocks(android, spec["theTwoDoors"]["android"]["integrated"]["anchors"][0])
    assert len(dialogs) == 1, "there is more than one DwInlineRecordDialog"
    dialog = dialogs[0]

    every = [record["android"]["form"] for record in android_records(spec)]
    screens = [
        block
        for block in kotlin_blocks(
            android, spec["theTwoDoors"]["android"]["standalone"]["anchors"][0]
        )
        if all(calls(block, name) for name in every)
    ]
    assert len(screens) == 1, (
        "exactly one `is Screen.Create -> when (s.mode)` block must mount all five record forms; "
        f"{len(screens)} of them do. See the contract's `theTwoDoors.android.standalone.why` — the "
        "anchor legitimately matches twice, and a second screen mounting a different set of forms "
        "is the standalone door splitting in two."
    )
    create = screens[0]

    for record in android_records(spec):
        name = record["android"]["form"]
        assert calls(create, name), f"Screen.Create no longer mounts `{name}`."
        if record["key"] == "interview":
            continue
        assert calls(dialog, name), f"DwInlineRecordDialog no longer mounts `{name}`."
    assert calls(dialog, "EditScreen"), (
        "DwInlineRecordDialog no longer reuses `EditScreen` for the edit case. Re-implementing "
        "'fetch the record, pick the form' in the dialog is two places for one decision."
    )



def test_every_record_body_carries_both_workshop_keys_and_reads_them_off_one_control():
    spec = contract()
    keys = spec["payloadKeys"]
    for record in web_records(spec):
        rel = record["web"]["form"]
        source = read(rel)
        for shape in keys["web"]["shape"]:
            assert shape in source, (
                f"{rel} does not build its payload as `{shape}`. Both keys are always sent and both "
                "come off the one picker; a form that reads a workshop from anywhere else is a form "
                "the two doors can disagree in."
            )

    android = read(str(ANDROID_MAIN.relative_to(_ROOT)).replace("\\", "/"))
    for record in android_records(spec):
        body, _ = kotlin_fun_body(android, record["android"]["form"])
        for shape in keys["android"]["shape"]:
            assert shape in body, (
                f"the handset's `{record['android']['form']}` does not build its body as `{shape}`."
            )


def test_no_record_body_carries_the_type_of_workshop():
    spec = contract()
    call = spec["savePath"]["web"]["call"]
    for record in web_records(spec):
        rel = record["web"]["form"]
        source = read(rel)
        at = source.index(call)
        block = _balanced(source, source.index("{", at), "{", "}")
        for forbidden in spec["payloadKeys"]["mustNotAppear"]:
            assert f"{forbidden}:" not in block, (
                f"{rel} sends `{forbidden}` in the record body. The type is NOT stored on the "
                "record (R3): the workshop it points at already knows its own type, and a second "
                "copy beside the record is one that can disagree with its own source."
            )

    android = read(str(ANDROID_MAIN.relative_to(_ROOT)).replace("\\", "/"))
    for record in android_records(spec):
        body, _ = kotlin_fun_body(android, record["android"]["form"])
        for forbidden in spec["payloadKeys"]["mustNotAppear"]:
            assert not re.search(rf"\b{forbidden}\s*=", body), (
                f"the handset's `{record['android']['form']}` sends `{forbidden}` in a request "
                "body. The type is not stored on the record (R3)."
            )



def test_the_destination_rule_is_written_exactly_once_on_each_client():
    spec = contract()
    rule = spec["destinationRule"]

    web = read(rule["web"]["file"])
    for expression in rule["web"]["expressions"]:
        assert expression in web, (
            f"{rule['web']['file']} no longer decides the destination as `{expression}`. R3 is the "
            "one rule this release turns on; it lives in the picker so that five forms and two "
            "hosts cannot each have their own version of it."
        )

    android = read(str(ANDROID_MAIN.relative_to(_ROOT)).replace("\\", "/"))
    for expression in rule["android"]["expressions"]:
        assert expression in android, (
            f"the handset no longer decides the destination as `{expression}`."
        )

    for record in web_records(spec):
        source = read(record["web"]["form"])
        assert "routesToDesignWorkshop ?" not in source, (
            f"{record['web']['form']} branches on `routesToDesignWorkshop` itself. That decision "
            "belongs to the picker: a form that makes it again is a form that can make it "
            "differently."
        )


def test_the_routing_of_an_existing_record_is_pinned_by_its_stored_columns():
    spec = contract()
    pinned = spec["destinationRule"]["existingRecordIsPinned"]

    web = read(pinned["web"]["file"])
    assert f"export function {pinned['web']['symbol']}" in web, (
        f"{pinned['web']['file']} no longer exports `{pinned['web']['symbol']}`, which is how an "
        "edit's type box learns the routing the record already has."
    )
    assert "isEdit" in web, f"{pinned['web']['file']} no longer knows whether it is on an edit."

    android = read(pinned["android"]["file"])
    assert f"fun {pinned['android']['symbol']}" in android, (
        f"{pinned['android']['file']} no longer declares `{pinned['android']['symbol']}`."
    )

    for record in web_records(spec):
        if record["web"]["update"] is None:
            continue
        source = read(record["web"]["form"])
        assert "initialDesignWorkshopId" in source and "isEdit" in source, (
            f"{record['web']['form']} no longer hands the picker the record's stored design "
            "workshop and its edit flag. Without both, an edit re-runs the create-time default over "
            "a filed record."
        )



def test_every_web_record_form_mounts_exactly_one_picker_and_neither_half_directly():
    spec = contract()
    controls = spec["workshopControls"]["web"]
    for rel in controls["forms"]:
        source = read(rel)
        mounts = [m.start() for m in re.finditer(rf"<{controls['picker']}[\s/>]", source)]
        assert len(mounts) == 1, (
            f"{rel} mounts <{controls['picker']}> {len(mounts)} times and the contract says once. "
            "R2 is two dropdowns per record form, never three and never four."
        )
        for half in controls["halves"]:
            assert not re.search(rf"<{half}[\s/>]", source), (
                f"{rel} mounts <{half}> directly, beside the picker. Both halves belong to "
                f"<{controls['picker']}>, which is what decides which of them is drawn and which "
                "column the answer is saved in."
            )


def test_every_android_record_form_mounts_exactly_one_picker_and_neither_half_directly():
    spec = contract()
    controls = spec["workshopControls"]["android"]
    android = read(str(ANDROID_MAIN.relative_to(_ROOT)).replace("\\", "/"))
    for name in controls["forms"]:
        body, at = kotlin_fun_body(android, name)
        mounts = calls(body, controls["picker"])
        assert len(mounts) == 1, (
            f"the handset's `{name}` (MainActivity.kt:{line_of(android, at)}) calls "
            f"`{controls['picker']}()` {len(mounts)} times and the contract says once."
        )
        for half in controls["halves"] + [controls["typeControl"]]:
            assert not calls(body, half), (
                f"the handset's `{name}` calls `{half}()` directly. It belongs to "
                f"`{controls['picker']}`, which is the one place the routing is decided."
            )


def test_both_clients_draw_the_same_two_labels_in_the_same_order():
    spec = contract()
    expected = spec["workshopControls"]["labels"]

    web = read(spec["workshopControls"]["web"]["file"])
    at = web.index(f"export function {spec['workshopControls']['web']['picker']}")
    drawn = re.findall(r'\blabel="([^"]*)"', web[at:])
    assert drawn[:1] == expected[:1], (
        f"the browser's picker draws {drawn[:1]} first and the contract says {expected[:1]}."
    )
    assert set(drawn) == set(expected), (
        f"the browser's picker draws the labels {sorted(set(drawn))}; the contract declares "
        f"{sorted(set(expected))}. Both arms of the workshop box pass the same word deliberately — "
        "the reader is answering one question, and a label that changed with the type would be the "
        "two-pickers-for-one-question problem this release exists to end."
    )

    android = read(str(ANDROID_MAIN.relative_to(_ROOT)).replace("\\", "/"))
    picker_body, _ = kotlin_fun_body(android, spec["workshopControls"]["android"]["picker"])
    order = [
        (picker_body.index(name), name)
        for name in [spec["workshopControls"]["android"]["typeControl"]]
        + spec["workshopControls"]["android"]["halves"]
        if name in picker_body
    ]
    order.sort()
    assert next(name for _, name in order) == spec["workshopControls"]["android"]["typeControl"], (
        "the handset draws its workshop box before its type box. The browser draws the type first "
        "and all three walkthrough registers are written in that order."
    )

    type_source = read(spec["workshopControls"]["android"]["typeControlFile"])
    assert f'label = "{expected[0]}"' in type_source, (
        f"the handset's type box no longer labels itself “{expected[0]}”."
    )
    assert (
        f'label = "{expected[1]}"' in android or f'label: String = "{expected[1]}"' in type_source
    ), f"the handset's workshop box no longer labels itself “{expected[1]}”."


def test_the_retired_third_control_is_gone_from_both_trees():
    spec = contract()
    for deleted in spec["workshopControls"]["deletedComponents"]:
        assert not (_ROOT / deleted["path"]).exists(), (
            f"{deleted['path']} is back in the tree. It was deleted on purpose: {deleted['why']}"
        )

    forbidden = spec["workshopControls"]["forbiddenSymbols"]
    web_tree = [
        path
        for folder in ("app", "components", "lib")
        for path in (_ROOT / "frontend" / folder).rglob("*.ts*")
        if "node_modules" not in path.parts and ".next" not in path.parts
    ]
    for path in web_tree:
        source = _strip_ts_comments(path.read_text(encoding="utf-8"))
        for name in forbidden["web"]:
            assert name not in source, (
                f"{path.relative_to(_ROOT)} still carries `{name}` in code. The retired kind "
                "cascade is gone; a helper that outlives its control is how the control comes back."
            )

    android = read(str(ANDROID_MAIN.relative_to(_ROOT)).replace("\\", "/"))
    picker = read(
        "android/app/src/main/java/com/designprototype/workshop/ui/DesignWorkshopPicker.kt"
    )
    for name in forbidden["android"]:
        assert name not in android and name not in picker, (
            f"`{name}` is back on the handset. It was the kind box's own vocabulary."
        )

    for record in web_records(spec):
        source = read(record["web"]["form"])
        for name in forbidden["inRecordFormsOnly"]:
            assert name not in source, (
                f'{record["web"]["form"]} mentions `{name}`. R2\'s "the workshops of that type" is '
                "honoured by the choice of TABLE and by nothing else: 13,847 of 13,871 "
                "`DesignWorkshop` rows carry no `workshopKind`, so narrowing by it would answer "
                '"no workshops of this type" about a table full of them.'
            )



def test_every_record_form_runs_the_submission_pre_flight_before_it_writes():
    spec = contract()
    web = spec["validation"]["web"]
    for record in web_records(spec):
        rel = record["web"]["form"]
        source = read(rel)
        gate = source.find(web["call"])
        write = source.find(web["before"] + "<")
        assert gate >= 0, (
            f"{rel} never calls `{web['call']}`. The workshop decides two things a researcher has "
            "to learn before they save: whether they are on the roster, and whether the window has "
            "closed."
        )
        assert gate < write, (
            f"{rel} calls `{web['call']}` at line {line_of(source, gate)}, AFTER the write at line "
            f"{line_of(source, write)}. A late-submission confirmation about a record that is "
            "already saved is not a gate."
        )

    android = read(str(ANDROID_MAIN.relative_to(_ROOT)).replace("\\", "/"))
    for record in android_records(spec):
        body, at = kotlin_fun_body(android, record["android"]["form"])
        gate = body.find(spec["validation"]["android"]["call"])
        assert gate >= 0, (
            f"the handset's `{record['android']['form']}` (MainActivity.kt:{line_of(android, at)}) "
            f"never calls `{spec['validation']['android']['call']}`."
        )
        create = body.find(record["android"]["createCall"])
        assert 0 <= gate < create, (
            f"the handset's `{record['android']['form']}` runs the pre-flight at offset {gate} and "
            f"writes at {create}. A late-submission confirmation about a record that is already "
            "saved is not a gate."
        )


def test_the_offline_queue_has_one_shape_per_client_and_both_doors_write_it():
    spec = contract()
    web = spec["offlineQueue"]["web"]
    source = read(web["file"])
    at = source.index(f"export type {web['type']} = {{")
    block = _balanced(source, source.index("{", at), "{", "}")
    for key in web["keys"]:
        assert re.search(rf"^\s*{key}[?:]", block, re.MULTILINE), (
            f"{web['file']}'s `{web['type']}` no longer declares `{key}`. The queue entry is built "
            "by the FORM, which is the same component on both doors — so a field lost here is lost "
            "for both of them."
        )

    android = read(spec["offlineQueue"]["android"]["file"])
    at = android.index(f"data class {spec['offlineQueue']['android']['type']}(")
    block = _balanced(android, android.index("(", at), "(", ")")
    for key in spec["offlineQueue"]["android"]["keys"]:
        assert re.search(rf"\bval {key}\s*:", block), (
            f"the handset's `{spec['offlineQueue']['android']['type']}` no longer declares `{key}`."
        )
    unfiled = spec["offlineQueue"]["android"]["unfiledMap"]
    for token in (unfiled["byChoice"], unfiled["noOptions"]):
        assert token in android, (
            f"`{token}` is gone from Offline.kt. The two absences it tells apart are a clearance "
            "the server must honour and a picker that simply could not list anything; collapsing "
            "them either destroys a stored link or invents a clearance nobody made."
        )

    dialog = read(spec["theTwoDoors"]["web"]["integrated"]["files"][0])
    assert web["queuedNotice"] in dialog, (
        f"the integrated dialog no longer takes `{web['queuedNotice']}`. Inside a dialog the outbox "
        "banner is behind the overlay on a body whose scroll the dialog has locked, so a queued "
        "save was indistinguishable from one that failed — which is how a designer banks three "
        "copies of one artisan."
    )



def test_every_declared_difference_is_still_a_difference():
    spec = contract()
    android = read(str(ANDROID_MAIN.relative_to(_ROOT)).replace("\\", "/"))

    for row in spec["declaredDifferences"]["rows"]:
        gone = (
            f"\n\n  GOOD NEWS (probably): the difference `{row['id']}` declared in "
            f"{CONTRACT_PATH.name} appears to be GONE. This test asserts that the contract's list of "
            "licensed differences is still accurate, so converging the two sides makes it red on "
            "purpose. Delete the row — or, if the difference moved rather than closed, say where."
        )
        still = row["stillTrue"]

        if "fileContains" in still:
            rel, needle = still["fileContains"]
            assert needle in read(rel), f"{rel} no longer contains {needle!r}." + gone

        if "kotlinDataClassFields" in still:
            declared = still["kotlinDataClassFields"]
            source = read(declared["file"])
            at = source.index(f"data class {declared['symbol']}(")
            block = _balanced(source, source.index("(", at), "(", ")")
            fields = re.findall(r"\bval (\w+)\s*:", block)
            assert fields == declared["exactly"], (
                f"`{declared['symbol']}` now declares {fields}; the contract says "
                f"{declared['exactly']}." + gone
            )

        if "androidFormContains" in still:
            declared = still["androidFormContains"]
            body, _ = kotlin_fun_body(android, declared["form"])
            assert declared["text"] in body, (
                f"the handset's `{declared['form']}` no longer says {declared['text']!r}." + gone
            )

        if "singleBoxForms" in still:
            for entry in still["singleBoxForms"]:
                if entry["client"] == "web":
                    source = read(entry["file"])
                    assert re.search(rf"<{entry['control']}[\s/>]", source), (
                        f"{entry['file']} no longer mounts <{entry['control']}>." + gone
                    )
                    assert not re.search(r"<WorkshopPicker[\s/>]", source), (
                        f"{entry['file']} now mounts the two-box picker. If the wire gained the "
                        "column that made that possible, this row is closed." + gone
                    )
                else:
                    body, _ = kotlin_fun_body(android, entry["form"])
                    assert calls(body, entry["control"]), (
                        f"the handset's `{entry['form']}` no longer calls `{entry['control']}()`."
                        + gone
                    )
                    assert not calls(body, "RecordWorkshopField"), (
                        f"the handset's `{entry['form']}` now draws the two-box picker." + gone
                    )


def test_every_artisan_create_says_which_likely_duplicates_it_was_shown():
    spec = contract()["artisanCreateKeys"]
    key = spec["key"]

    web = read(spec["web"]["form"])
    assert spec["web"]["shape"] in web, (
        f"{spec['web']['form']} does not send `{key}` as `{spec['web']['shape']}`. Every create says "
        "what it was shown (`[]` when nothing was), and an edit never sends it."
    )

    android = read(str(ANDROID_MAIN.relative_to(_ROOT)).replace("\\", "/"))
    body, _ = kotlin_fun_body(android, spec["android"]["form"])
    assert spec["android"]["shape"] in body, (
        f"the handset's `{spec['android']['form']}` does not send `{key}` as "
        f"`{spec['android']['shape']}`."
    )

    schemas = read(spec["schemaFile"])
    create_at = schemas.index(f"class {spec['createSchema']}(")
    update_at = schemas.index(f"class {spec['updateSchema']}(")
    create_block = schemas[create_at:update_at] if create_at < update_at else schemas[create_at:]
    assert f"{key}:" in create_block, f"{spec['createSchema']} does not declare `{key}`."
    after_update = schemas[update_at:]
    next_class = after_update.find("\nclass ", 1)
    update_block = after_update if next_class == -1 else after_update[:next_class]
    assert f"{key}:" not in update_block, (
        f"{spec['updateSchema']} declares `{key}`. Only a create is ever held as a likely duplicate."
    )


def test_the_contract_is_internally_consistent():
    spec = contract()
    keys = [record["key"] for record in spec["records"]]
    assert len(keys) == len(set(keys)), f"{CONTRACT_PATH.name} declares a record key twice: {keys}"

    for record in spec["records"]:
        assert record["columns"] == ["workshopId", "designWorkshopId"], (
            f"{CONTRACT_PATH.name} gives “{record['key']}” the columns {record['columns']}. R1 "
            "keeps both tables and both nullable foreign keys on every record that has them; a "
            "record with one column is not a two-dropdown form and belongs in "
            "`declaredDifferences`, not here."
        )
        assert record["android"]["form"], f"{record['key']} names no android form"
        if record["web"] is None:
            assert record.get("webRetired"), f"{record['key']} has no web form and does not say why"
        else:
            assert record["web"]["form"], f"{record['key']} names no web form"

    for row in spec["declaredDifferences"]["rows"]:
        assert row.get("stillTrue") and row.get("why") and row.get("id"), (
            f"the declaredDifferences row {row.get('id')!r} is missing `stillTrue`, `why` or `id`. "
            "A licensed difference with no measurement behind it cannot be asserted, and one with "
            "no reason beside it is not a licence."
        )

    forms = {record["web"]["form"] for record in web_records(spec)}
    declared = set(spec["workshopControls"]["web"]["forms"])
    assert forms == declared, (
        f"{CONTRACT_PATH.name} declares {len(forms)} records and {len(declared)} web forms carrying "
        "the picker, and they are not the same set:\n"
        f"    records only: {sorted(forms - declared)}\n"
        f"    controls only: {sorted(declared - forms)}\n"
        "Every record with both columns draws the two-box picker, and nothing else does."
    )


if __name__ == "__main__":  # pragma: no cover - convenience
    raise SystemExit(pytest.main([__file__, "-q"]))

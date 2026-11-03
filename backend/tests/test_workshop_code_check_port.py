from app.services.design_workshop_access import code_check

BROWSER_VECTORS: tuple[tuple[str, str], ...] = (
    ("DPW1:G:CMSIK2JG8000EH8XC1LCY661A", "0PK3"),
    ("DPW1:A:CMSIK2JG8000EH8XC1LCY661A", "NEWD"),
    ("DPW1:G:CLXQ9WZ7T0001PQ4R2VN8K3JD", "JSVH"),
    ("DPW1:G:3F2504E0-4F89-11D3-9A0C-0305E82C3301", "R0R5"),
    ("DPW1:G:AAAAAAAA", "30KG"),
    ("", "S7E5"),
)


def test_the_check_matches_the_browsers_own_vectors():
    for prefix, expected in BROWSER_VECTORS:
        assert code_check(prefix) == expected, prefix


def test_the_check_is_four_crockford_characters_for_anything_it_is_handed():
    alphabet = set("0123456789ABCDEFGHJKMNPQRSTVWXYZ")
    for prefix in ("", "A", "DPW1:G:AAAAAAAA", "DPW1:G:" + "Z" * 64, "DPW1:G:0"):
        answer = code_check(prefix)
        assert len(answer) == 4, prefix
        assert set(answer) <= alphabet, (prefix, answer)

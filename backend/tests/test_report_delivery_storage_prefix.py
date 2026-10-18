"""The office's report file must land where the API's key may write it, and nowhere public.

In production the API's IAM user (``infra/terraform/main.tf``, ``aws_iam_user_policy.media``) may
write ``media/*``, ``aadhaar-log-archive/*`` and ``report-deliveries/*`` only, and the bucket policy
makes everything under ``media/`` world-readable. A key outside the granted prefixes answers
AccessDenied (every "Approve and hand on" became a 503); a key under ``media/`` would publish an
approved report naming artisans. This pins the key to the one prefix that is both.
"""

from __future__ import annotations

import re
from pathlib import Path

from app.services import report_delivery

TERRAFORM = Path(__file__).resolve().parents[2] / "infra" / "terraform" / "main.tf"


def _statement(text: str, sid: str) -> str:
    match = re.search(r'Sid\s*=\s*"' + sid + r'"(.*?)\n\s*\}', text, re.S)
    assert match, f"no {sid} statement in {TERRAFORM}"
    return match.group(1)


def test_storage_key_is_under_the_granted_private_prefix() -> None:
    key = report_delivery.storage_key_for("wk123", "Report — Barpali.pdf")
    assert key.startswith("report-deliveries/wk123/")
    assert not key.startswith("media/")


def test_terraform_grants_put_and_get_on_that_prefix_only() -> None:
    text = TERRAFORM.read_text(encoding="utf-8")
    body = _statement(text, "ReportDeliveries")
    assert '"${aws_s3_bucket.media.arn}/report-deliveries/*"' in body
    assert '"s3:PutObject"' in body and '"s3:GetObject"' in body
    assert "DeleteObject" not in body and "ListBucket" not in body
    public = _statement(text, "PublicReadMedia")
    assert "report-deliveries" not in public

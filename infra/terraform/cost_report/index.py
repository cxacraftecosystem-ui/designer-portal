"""Monthly AWS expenditure report, mailed through SNS.

WHY THIS EXISTS. AWS has no API for a scheduled cost report. `create-budgets-report` is console-only
and Cost Explorer's only subscription type is anomaly detection (DAILY / WEEKLY / IMMEDIATE, and
anomaly-triggered rather than periodic). Budgets alert on a THRESHOLD being crossed; they never tell
you what a month actually cost. So the periodic "what did we spend" mail is this function.

WHAT IT REPORTS, AND WHY THE SPLIT IS THE POINT. This account's usage has been entirely absorbed by
promotional credits since it was opened — every month from 2026-06 onward reads usage $X, credits
-$X, net $0.00. A report showing only net cost would print "$0.00" every month and say nothing,
right up until the month the credits run out and the card on file is charged without warning. So the
mail carries all three numbers: GROSS usage, CREDITS applied, and NET — the last being the only one
that is actually billed to a payment method.

THE NUMBER THIS CANNOT REPORT is the remaining credit balance, which is what would give real
warning. AWS exposes it in the Billing console under Credits and through no API at all (checked
2026-09-17: neither `ce`, `budgets`, `billing` nor `freetier` returns it). The report says so in its
own body rather than leaving a reader to assume the absence means zero.
"""

import datetime
import os
from collections import defaultdict

import boto3

# COST EXPLORER IS A us-east-1 SERVICE AND THIS IS NOT OPTIONAL. The `ce` endpoint exists only
# there; a client built from the Lambda's own region (ap-south-1) raises EndpointConnectionError.
# The SNS client, by contrast, must speak to the topic's region, which is the function's own.
CE = boto3.client("ce", region_name="us-east-1")
SNS = boto3.client("sns")

TOPIC_ARN = os.environ["TOPIC_ARN"]
ACCOUNT_ID = os.environ["ACCOUNT_ID"]


def _last_month(today):
    """First and last-exclusive day of the calendar month before `today`, as CE wants them.

    CE's `End` is EXCLUSIVE, so a report for August is Start=2026-08-01, End=2026-09-01. Passing
    the last day of the month instead silently drops that day's spend, which is the kind of quiet
    undercount nobody notices until a reconciliation.
    """
    first_of_this = today.replace(day=1)
    last_of_prev = first_of_this - datetime.timedelta(days=1)
    return last_of_prev.replace(day=1), first_of_this


def _money(x):
    """Format as USD, and never as the string "$-0.00".

    Credits are applied per line item, so gross and credits cancel to a residue rather than to zero
    — the first live run reported net -2.677e-07 for a fully credited month. Formatted naively that
    prints "$-0.00", which reads as a broken report and invites somebody to go looking for a
    rounding bug that is not there. Anything inside half a cent of zero IS zero for a billing
    statement, so it is normalised here rather than at each call site.
    """
    rounded = round(x, 2)
    if rounded == 0:
        rounded = 0.0
    return "${:,.2f}".format(rounded)


def handler(event, context):
    today = datetime.date.today()
    start, end = _last_month(today)
    label = start.strftime("%B %Y")

    # ── 1. GROSS, CREDITS AND NET, FROM ONE CALL ────────────────────────────────────────────────
    # Grouping by RECORD_TYPE is what separates what was USED from what was PAID. "Usage" is the
    # list price of what ran; "Credit" is the negative amount promotional credit absorbed; anything
    # else (Refund, Tax, SavingsPlan…) is reported under its own name rather than folded into one
    # of those two, so a record type nobody anticipated cannot silently vanish from the arithmetic.
    by_type = defaultdict(float)
    resp = CE.get_cost_and_usage(
        TimePeriod={"Start": start.isoformat(), "End": end.isoformat()},
        Granularity="MONTHLY",
        Metrics=["UnblendedCost"],
        GroupBy=[{"Type": "DIMENSION", "Key": "RECORD_TYPE"}],
    )
    for period in resp.get("ResultsByTime", []):
        for group in period.get("Groups", []):
            by_type[group["Keys"][0]] += float(group["Metrics"]["UnblendedCost"]["Amount"])

    gross = by_type.get("Usage", 0.0)
    credits = by_type.get("Credit", 0.0)          # already negative
    net = sum(by_type.values())                   # every record type, not just the two named

    # ── 2. WHERE THE USAGE WENT ─────────────────────────────────────────────────────────────────
    # Filtered to Usage records so the per-service figures are comparable with `gross` above. Without
    # the filter the credit lines land on the same services and every row reads about zero, which
    # looks like an idle account rather than a fully-credited one.
    by_service = defaultdict(float)
    resp = CE.get_cost_and_usage(
        TimePeriod={"Start": start.isoformat(), "End": end.isoformat()},
        Granularity="MONTHLY",
        Metrics=["UnblendedCost"],
        Filter={"Dimensions": {"Key": "RECORD_TYPE", "Values": ["Usage"]}},
        GroupBy=[{"Type": "DIMENSION", "Key": "SERVICE"}],
    )
    for period in resp.get("ResultsByTime", []):
        for group in period.get("Groups", []):
            amount = float(group["Metrics"]["UnblendedCost"]["Amount"])
            if abs(amount) >= 0.01:
                by_service[group["Keys"][0]] += amount

    lines = [
        "AWS expenditure for {label}".format(label=label),
        "Account {acct}".format(acct=ACCOUNT_ID),
        "",
        "  Usage (list price)   {v}".format(v=_money(gross)),
        "  Credits applied      {v}".format(v=_money(credits)),
        "  " + "-" * 32,
        "  NET, ACTUALLY BILLED {v}".format(v=_money(net)),
        "",
    ]

    if net < 0.01:
        lines += [
            "Nothing was charged to the payment method on file: promotional credits",
            "absorbed the whole of this month's usage.",
            "",
            "The remaining credit BALANCE is the number that decides how long that lasts,",
            "and AWS publishes it through no API - read it in the Billing console under",
            "Credits. At this month's usage the balance divided by {v} is roughly how".format(v=_money(gross)),
            "many months of runway are left.",
            "",
        ]
    else:
        lines += [
            "*** {v} WAS CHARGED TO THE PAYMENT METHOD ON FILE. ***".format(v=_money(net)),
            "",
            "Credits no longer cover the whole bill. Check the remaining balance and",
            "expiry in the Billing console under Credits.",
            "",
        ]

    lines.append("Usage by service:")
    for name, amount in sorted(by_service.items(), key=lambda kv: -kv[1]):
        lines.append("  {v:>10}  {n}".format(v=_money(amount), n=name))
    if not by_service:
        lines.append("  (no service exceeded $0.01)")

    lines += [
        "",
        "Figures are UnblendedCost from Cost Explorer, read on {d}.".format(d=today.isoformat()),
        "AWS keeps adjusting a closed month for a few days, so a report run early in",
        "the month can move slightly afterwards.",
    ]

    body = "\n".join(lines)
    SNS.publish(
        TopicArn=TOPIC_ARN,
        Subject="AWS spend {label}: {net} billed".format(label=start.strftime("%b %Y"), net=_money(net))[:100],
        Message=body,
    )
    return {"month": label, "gross": gross, "credits": credits, "net": net}

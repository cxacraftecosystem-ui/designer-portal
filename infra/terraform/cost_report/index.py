import datetime
import os
from collections import defaultdict

import boto3

CE = boto3.client("ce", region_name="us-east-1")
SNS = boto3.client("sns")

TOPIC_ARN = os.environ["TOPIC_ARN"]
ACCOUNT_ID = os.environ["ACCOUNT_ID"]


def _last_month(today):
    first_of_this = today.replace(day=1)
    last_of_prev = first_of_this - datetime.timedelta(days=1)
    return last_of_prev.replace(day=1), first_of_this


def _money(x):
    rounded = round(x, 2)
    if rounded == 0:
        rounded = 0.0
    return "${:,.2f}".format(rounded)


def handler(event, context):
    today = datetime.date.today()
    start, end = _last_month(today)
    label = start.strftime("%B %Y")

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
    credits = by_type.get("Credit", 0.0)
    net = sum(by_type.values())

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

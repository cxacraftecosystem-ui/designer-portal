###############################################################################
# MONTHLY AWS EXPENDITURE REPORT
#
# A scheduled Lambda that reads Cost Explorer for the month just ended and mails
# the figures through SNS. `cost_report/index.py` carries the argument for what
# it reports and why the gross/credits/net split is the whole point; this file
# is only the plumbing.
#
# ─── WHY THIS IS BUILT RATHER THAN CONFIGURED ────────────────────────────────
# AWS has no API for a periodic cost report. Budgets Reports — the console
# feature that does exactly this — is not in the Budgets API (checked
# 2026-09-17: `aws budgets help` lists no report verb), and Cost Explorer's only
# subscription type is anomaly detection, which is event-driven rather than
# periodic. The two AWS Budgets on this account alert when a THRESHOLD is
# crossed; neither can say what a month cost. So the periodic mail is four small
# resources and sixty lines of Python, or it is somebody remembering to open the
# console.
#
# ─── IT REPORTS THE WHOLE ACCOUNT, NOT THIS PROJECT ──────────────────────────
# Account 626159998512 also runs `fieldrepo-api` and `colloquia-api`, and the
# figures below cover all three because the question being answered is "what will
# be charged to the card on file", which is an account-level fact. The resources
# are still named `${var.project}-*` because they are declared from this
# workspace's state and nothing else may collide with them. If a sibling
# deployment ever wants its own copy, the two would double-report — add a
# `CostFilters`-style tag filter to the Lambda first, do not simply apply twice.
#
# ─── COST OF THE THING THAT MEASURES COST ────────────────────────────────────
# Cost Explorer charges $0.01 per paginated API request, and this makes two per
# month. The Lambda is one invocation of a few seconds a month, inside the free
# allowance. Call it a cent a month, which is worth saying out loud because a
# monitoring component that costs real money is a thing people discover late.
###############################################################################

resource "aws_sns_topic" "cost_report" {
  name = "${var.project}-cost-report"

  tags = {
    Name    = "${var.project}-cost-report"
    Project = var.project
  }
}

# EACH ADDRESS MUST CLICK "Confirm subscription" IN ITS OWN MAILBOX, AND UNTIL IT DOES IT GETS
# NOTHING. That is SNS's design and Terraform cannot do it for you: an email subscription is created
# in `PendingConfirmation` and stays there. `terraform apply` will therefore report success over a
# subscription that delivers nothing, which is the same shape of quiet lie this repository's deploy
# pipeline spent 2026-09-17 removing — so `terraform output cost_report_subscriptions` prints the
# real per-address state, and the confirmation link expires after three days.
resource "aws_sns_topic_subscription" "cost_report" {
  for_each = toset(var.cost_report_emails)

  topic_arn = aws_sns_topic.cost_report.arn
  protocol  = "email"
  endpoint  = each.value
}

resource "aws_iam_role" "cost_report" {
  name = "${var.project}-cost-report"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "lambda.amazonaws.com" }
      Action    = "sts:AssumeRole"
    }]
  })

  tags = {
    Name    = "${var.project}-cost-report"
    Project = var.project
  }
}

# ce:GetCostAndUsage IS RESOURCE "*" BECAUSE COST EXPLORER HAS NO RESOURCE ARNS — the service does
# not support resource-level permissions, so "*" here is the narrowest grant the API accepts rather
# than a shortcut. The one verb the function actually calls is named exactly, NOT `ce:Get*`: this
# role must never be able to call `ce:CreateAnomalySubscription` or the Cost Category write verbs,
# and a wildcard would quietly include whatever AWS adds under that prefix next.
#
# The SNS and logs grants are narrowed to this topic and this log group by ARN, which is the reason
# the log group is declared in this file rather than left for Lambda to create — see its comment.
resource "aws_iam_role_policy" "cost_report" {
  name = "${var.project}-cost-report"
  role = aws_iam_role.cost_report.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect   = "Allow"
        Action   = ["ce:GetCostAndUsage"]
        Resource = "*"
      },
      {
        Effect   = "Allow"
        Action   = ["sns:Publish"]
        Resource = aws_sns_topic.cost_report.arn
      },
      {
        Effect   = "Allow"
        Action   = ["logs:CreateLogStream", "logs:PutLogEvents"]
        Resource = "${aws_cloudwatch_log_group.cost_report.arn}:*"
      },
    ]
  })
}

data "archive_file" "cost_report" {
  type        = "zip"
  source_file = "${path.module}/cost_report/index.py"
  output_path = "${path.module}/.terraform/tmp/cost_report.zip"
}

# THE LOG GROUP IS DECLARED, NOT LEFT TO LAMBDA. A function that creates its own log group gets one
# with retention "Never expire", which is a slow storage leak and an unbounded one — and because
# Lambda creates it on first invocation, Terraform would later refuse to manage a group it did not
# make. Declaring it here also lets the policy above grant logs on THIS group rather than on "*".
resource "aws_cloudwatch_log_group" "cost_report" {
  name              = "/aws/lambda/${var.project}-cost-report"
  retention_in_days = 90

  tags = {
    Name    = "${var.project}-cost-report"
    Project = var.project
  }
}

resource "aws_lambda_function" "cost_report" {
  function_name = "${var.project}-cost-report"
  role          = aws_iam_role.cost_report.arn
  handler       = "index.handler"
  runtime       = "python3.12"

  filename = data.archive_file.cost_report.output_path
  # WITHOUT THIS THE CODE NEVER UPDATES. Terraform compares the zip's hash, not its contents; omit
  # `source_code_hash` and an edit to index.py produces "No changes" for ever, and the fix looks
  # like "Terraform is broken" rather than "the attribute is missing".
  source_code_hash = data.archive_file.cost_report.output_base64sha256

  # Two Cost Explorer calls against a cold start. CE is occasionally slow to answer for a freshly
  # closed month; 60s is generous and the function is charged by the millisecond, so the ceiling
  # costs nothing until it is actually needed.
  timeout = 60

  environment {
    variables = {
      TOPIC_ARN  = aws_sns_topic.cost_report.arn
      ACCOUNT_ID = data.aws_caller_identity.current.account_id
    }
  }

  # The policy grants logs on this group by name, and Lambda writes its first line before any code
  # runs — so the group has to exist first or the opening invocation logs nothing and the failure is
  # invisible in exactly the place you would look for it.
  depends_on = [aws_cloudwatch_log_group.cost_report]

  tags = {
    Name    = "${var.project}-cost-report"
    Project = var.project
  }
}

# THE 3rd, NOT THE 1st. AWS keeps adjusting a closed month for several days — late usage records,
# tax, credit application — so a report run at midnight on the 1st is a guess that then moves. The
# 3rd is late enough for the figures to have settled and early enough that a month whose credits ran
# out is still news. 06:00 UTC is 11:30 IST.
resource "aws_cloudwatch_event_rule" "cost_report" {
  name                = "${var.project}-cost-report-monthly"
  description         = "Mail the previous month's AWS expenditure on the 3rd of each month"
  schedule_expression = "cron(0 6 3 * ? *)"

  tags = {
    Name    = "${var.project}-cost-report-monthly"
    Project = var.project
  }
}

resource "aws_cloudwatch_event_target" "cost_report" {
  rule      = aws_cloudwatch_event_rule.cost_report.name
  target_id = "lambda"
  arn       = aws_lambda_function.cost_report.arn
}

resource "aws_lambda_permission" "cost_report" {
  statement_id  = "AllowExecutionFromEventBridge"
  action        = "lambda:InvokeFunction"
  function_name = aws_lambda_function.cost_report.function_name
  principal     = "events.amazonaws.com"
  source_arn    = aws_cloudwatch_event_rule.cost_report.arn
}

# Read rather than hardcoded, so the report's own header cannot drift from the account it is
# reporting on — a cost mail naming the wrong account is worse than no cost mail.
data "aws_caller_identity" "current" {}

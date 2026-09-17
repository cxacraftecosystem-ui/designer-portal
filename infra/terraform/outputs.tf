output "api_public_ip" {
  description = "Stable Elastic IP of the API server. Point the apps at http://<ip>/api/."
  value       = aws_eip.api.public_ip
}

output "s3_bucket" {
  description = "Media bucket name (set AWS_S3_BUCKET to this)."
  value       = aws_s3_bucket.media.bucket
}

output "s3_public_base_url" {
  description = "Set AWS_S3_PUBLIC_BASE_URL to this."
  value       = "https://${aws_s3_bucket.media.bucket}.s3.${var.aws_region}.amazonaws.com"
}

# HOW A HUMAN GETS ON THE BOX, PRINTED WHERE THE ADDRESS AND THE BUCKET ARE PRINTED. The command is
# an output rather than a line in a runbook because it needs the instance id, which nothing else here
# reports and which is otherwise looked up by hand every time. No inbound port is involved: this
# works from any network, and it is the reason the security group has no SSH rule.
output "ssm_session_command" {
  description = "Open a shell on the API box from anywhere (no SSH, no open port; IAM-authorised)."
  value       = "aws ssm start-session --target ${aws_instance.api.id} --region ${var.aws_region}"
}

output "media_access_key_id" {
  description = "IAM access key id for the API (AWS_ACCESS_KEY_ID)."
  value       = aws_iam_access_key.media.id
}

output "media_secret_access_key" {
  description = "IAM secret key for the API (AWS_SECRET_ACCESS_KEY). Sensitive."
  value       = aws_iam_access_key.media.secret
  sensitive   = true
}

# THE ONE OUTPUT THAT REPORTS A TRUTH `apply` CANNOT. An SNS email subscription is created in
# `PendingConfirmation` and delivers nothing until the recipient clicks the link AWS mails them;
# Terraform reports the resource as created either way. Read this after applying, and after anyone
# is added to `cost_report_emails` — a pending row is a recipient who thinks they are covered and
# is not. The link expires after three days, and re-running `apply` on a still-pending
# subscription does not send a new one; delete and recreate that one subscription to re-send.
output "cost_report_subscriptions" {
  description = "Per-address SNS subscription state for the monthly cost report. 'PendingConfirmation' means that address receives NOTHING yet."
  value = {
    for address, sub in aws_sns_topic_subscription.cost_report :
    address => sub.arn
  }
}

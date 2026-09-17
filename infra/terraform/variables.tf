variable "aws_region" {
  description = "AWS region for S3 + EC2 (keep S3 and the API in the same region)."
  type        = string
  default     = "ap-south-1"
}

variable "project" {
  description = "Name prefix for created resources."
  type        = string
  # DELIBERATELY NOT REBRANDED TO "design-workshop". This default is not a label — it is the primary
  # identifier of resources that already exist in the account, and `terraform.tfstate` in this
  # directory records them: the IAM user `fieldrepo-media`, its inline policy `fieldrepo-media-s3`,
  # and the security group `fieldrepo-api`. An IAM user's name cannot be changed in place, so the
  # next `terraform apply` after an edit here is a DESTROY-AND-CREATE: the old user goes away, and
  # with it `aws_iam_access_key.media` — the exact AWS_ACCESS_KEY_ID/AWS_SECRET_ACCESS_KEY pair
  # sitting in the running API's `/home/ubuntu/app/backend/.env`. The live box keeps serving with
  # credentials that no longer resolve, so every presign and every media upload starts returning
  # InvalidAccessKeyId while /health stays green, and nothing in the API's own logs points at
  # Terraform as the cause. The security group is replaced too, which detaches and reattaches the
  # instance's only ingress path.
  #
  # Note this prefix does NOT name the media bucket: `bucket_name` is a separate, required variable
  # — this portal's designrepo workspace passes `designrepo-media-626159998512`, and the sibling
  # field repository has its own `fieldrepo-media-…`; the comment named the SIBLING's bucket until
  # 2026-08-22 — because an S3 bucket name is globally unique and
  # replacing one would strand every uploaded photograph rather than move it.
  #
  # Renaming this is a migration, not an edit: create the new IAM user, roll the new key into the
  # box's .env, confirm uploads, then remove the old one.
  #
  # ─── "designrepo", NOT "fieldrepo", SINCE 2026-09-17 — AND THE OLD DEFAULT WAS A LOADED GUN ───
  # This defaulted to "fieldrepo", the SIBLING deployment, in the repository whose every resource is
  # named `designrepo-*`. Nothing passed it, because nothing had to: a default is silent. So
  # `terraform plan` in this directory did not fail, it proposed RENAMING THE LIVE ESTATE —
  #
  #     Plan: 6 to add, 3 to change, 6 to destroy
  #
  # — with `aws_security_group.api` replaced (a new sg id, and deploy-backend.yml's port-22 window
  # is written against the old one) and `aws_iam_user_policy.media` replaced with a fresh
  # `media_secret_access_key`, which silently breaks every media upload until BACKEND_ENV is
  # re-pasted. It was caught by reading a plan before applying it, which is the only reason this
  # comment is about a near miss rather than about an outage.
  #
  # Two things changed together and BOTH matter: this default now names the deployment this
  # repository actually manages, and `designrepo.auto.tfvars` beside it is now COMMITTED (with a
  # named exception in .gitignore) so the value is explicit rather than inherited. The default is
  # the belt; the tfvars file is the braces.
  #
  # IF YOU COPY THIS DIRECTORY TO A THIRD DEPLOYMENT, change this line in the same commit as the
  # copy. A default that is right for one estate and silently wrong for another is precisely the
  # defect being fixed here, and moving it from "fieldrepo" to "designrepo" does not cure that
  # shape — it only points it at this repository instead of away from it. The safest form is no
  # default at all, matching `bucket_name` and `ssh_key_name` below, which are required and which
  # is why a missing value for THEM produced a loud error instead of a destructive plan.
  default = "designrepo"
}

variable "bucket_name" {
  description = "Globally-unique S3 bucket name for media."
  type        = string
}

variable "ssh_key_name" {
  description = "Name of an existing EC2 key pair for SSH into the API box."
  type        = string
}

variable "ssh_ingress_cidr" {
  description = "CIDR allowed to SSH. EMPTY (the default) means port 22 is closed — use SSM instead."
  type        = string

  # EMPTY BY DEFAULT SINCE 2026-08-30, WHICH MEANS NO PORT-22 RULE IS CREATED AT ALL.
  #
  # It used to be REQUIRED — no default, so every apply had to name a network — and the value it was
  # applied with was the operator's home address of the day, `49.47.131.192/32`. Two failures came
  # out of that. GitHub's runners are in Azure ranges, so every scheduled deploy died at
  # `ssh-keyscan` and that workflow had never once succeeded; and the operator travels, so the
  # address stopped matching whenever they moved, with nothing on screen connecting "cannot reach
  # the box" to "you are on a different network". Deployability was a property of where somebody was
  # standing.
  #
  # The box is now reached through SSM Session Manager — no inbound port, IAM-authorised, audited in
  # CloudTrail — and `main.tf` makes the argument in full beside the security group. The rule is
  # emitted by a `dynamic "ingress"` block that produces nothing for an empty string, so the default
  # state is CLOSED and reopening SSH is an explicit act:
  #
  #     terraform apply -var="ssh_ingress_cidr=YOUR.IP/32"
  #
  # Kept rather than deleted for two reasons. Every runbook and command line that still passes it
  # keeps working — Terraform hard-errors on a value for an undeclared variable, so removing it
  # would break `backend/DEPLOY_AWS.md`'s documented invocation. And SSM is a dependency like any
  # other: the emergency where it is the broken thing is exactly when an operator needs a door that
  # does not go through it.
  #
  # DO NOT SET THIS TO "0.0.0.0/0". It would work, it would satisfy "deploy from anywhere", and it
  # would leave a permanently open SSH port on the box holding the production API's credentials. CI
  # already solves the same problem correctly by opening a /32 for its own runner and revoking it in
  # an `if: always()` step.
  default = ""
}

variable "cors_allowed_origins" {
  description = "Origins allowed to PUT/GET media from the browser (the web frontend URL)."
  type        = list(string)

  # THE DEFAULT IS THE REAL PAIR, NOT `["*"]`, AND BOTH HALVES OF THAT CHANGE ARE DELIBERATE.
  #
  # It read `["*"]` until 2026-08-30. Two problems with that as a default. First, a wildcard means
  # any page on the internet that gets hold of a presigned URL can spend it from a victim's browser
  # — the URL carries its own authorisation, so the origin list is the only thing narrowing who may
  # use one. Second, and the reason it changed on this particular day: the live bucket was found
  # allowing `https://design-repository.vercel.app` — one word off from the site's real host — which
  # refused every upload in production. A default of `["*"]` would have masked that class of typo
  # forever rather than making the correct value the obvious one.
  #
  # KEEP THIS IN STEP WITH `BACKEND_CORS_ORIGINS`, which `.github/workflows/deploy-backend.yml` pins
  # to the same two origins. They are two enforcement points for one question — "which browser is
  # the web client" — and the outage on 2026-08-30 was precisely what happens when one is corrected
  # and the other is not. Adding a frontend deployment means adding it in BOTH places, in one change.
  default = [
    "https://designer-repository.vercel.app",
    "http://localhost:3000",
  ]
}

variable "cost_report_emails" {
  description = "Addresses that receive the monthly AWS expenditure mail. EACH MUST CONFIRM ITS OWN SNS SUBSCRIPTION before it receives anything — see cost_report.tf."
  type        = list(string)

  # Not a secret: this address is already in `.env.example` as MASTER_ADMIN_EMAIL, and an SNS
  # subscription is worthless to anyone who cannot read the mailbox anyway. Kept as a variable
  # rather than inlined so adding a recipient is a one-line change with a plan behind it.
  default = ["ankits1802@gmail.com"]
}

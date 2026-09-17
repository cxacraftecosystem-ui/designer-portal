# The designrepo workspace's values. `.auto.tfvars` is loaded by every plan and apply in this
# directory without a -var flag, which is the whole point of the file.
#
# ─── WHY THIS IS COMMITTED WHEN `.gitignore` REFUSES EVERY OTHER .tfvars ─────────────────────────
#
# Because without it, `terraform plan` here was actively dangerous. `var.project` defaulted to
# "fieldrepo" — the SIBLING deployment — while every resource in this account is named
# `designrepo-*`, and a default is silent: the plan did not fail, it proposed renaming the live
# estate. Measured on 2026-09-17, in this directory, against the real state:
#
#     Plan: 6 to add, 3 to change, 6 to destroy
#
# including `aws_security_group.api` REPLACED (a new sg id, and `deploy-backend.yml`'s port-22
# deploy window is written against the old one) and `aws_iam_user_policy.media` REPLACED with a
# fresh `media_secret_access_key` — which would silently break every media upload until somebody
# re-pasted BACKEND_ENV. With these three values set, the same plan says "No changes. Your
# infrastructure matches the configuration."
#
# It was caught by reading a plan before applying it. That is the only control that stood between a
# routine change and a rebuilt security group, so this file exists to stop the next person needing
# to be as lucky.
#
# ─── NOTHING HERE IS SECRET, AND THE BLANKET RULE STILL STANDS ───────────────────────────────────
#
# A project prefix, a bucket name and an EC2 key-pair name — all three are already written out in
# `main.tf`'s own comments, and none of them authenticates anything. `.gitignore` keeps ignoring
# every other `*.tfvars`, because that is where credentials genuinely end up; this one path is a
# named exception (`!infra/terraform/designrepo.auto.tfvars`) with the reason recorded beside it.
# DO NOT add a credential to this file. If a value here ever needs to be secret, it does not belong
# in a committed tfvars at all — pass it with `-var` or move it to an Actions secret.
#
# `var.project` now also DEFAULTS to "designrepo", so a clone missing this file is merely explicit
# rather than dangerous. Both were changed together on purpose: the default is the belt, this file
# is the braces, and a reader who sees only one of them still gets the right estate.

project      = "designrepo"
bucket_name  = "designrepo-media-626159998512"
ssh_key_name = "designrepo-deploy"

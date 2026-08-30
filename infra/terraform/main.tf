###############################################################################
# Design Prototype Workshop infrastructure: S3 (media) + IAM (media access)
# + EC2 t3.micro (FastAPI behind nginx). This Terraform provisions NO DATABASE:
# the box points at a managed PostgreSQL named only by DATABASE_URL in the
# backend's environment, which is why the box is stateless and can be rebuilt
# anytime without data loss. Which provider that is, is a deployment fact and is
# recorded in one place — see "The database" in docs/ENVIRONMENT.md.
#
# Usage:
#   cd infra/terraform
#   terraform init
#   terraform apply \
#     -var="project=designrepo" \
#     -var="aws_region=ap-south-1" \
#     -var="bucket_name=designrepo-media-626159998512" \
#     -var="ssh_key_name=designrepo-deploy"
#
# `ssh_ingress_cidr` is no longer passed and no longer required: port 22 is
# CLOSED on this deployment and the way in is SSM. See the security group.
#
# NEVER commit terraform.tfstate or *.tfvars (already gitignored): state can
# contain the generated IAM secret key.
#
###############################################################################
# THIS FILE HAS BEEN OUT OF STEP WITH THE LIVE ACCOUNT, AND CATCHING UP IS AN
# IMPORT RATHER THAN AN APPLY. READ THIS BEFORE RUNNING ANYTHING.
###############################################################################
#
# On 2026-08-30 three things were changed directly against the live account —
# during an upload outage and a lockout, with no state file to hand — and this
# configuration was then corrected to describe what is actually there:
#
#   1. The media bucket's CORS document (see the block below; it named an origin
#      that does not exist and refused every upload in production).
#   2. An SSM instance profile, `designrepo-ssm`, created and attached to the
#      API instance so the box can be reached without an inbound port.
#   3. The static port-22 rule DELETED from the `designrepo-api` security group.
#
# The consequence for anybody running Terraform here: `aws_iam_role.ssm`,
# `aws_iam_role_policy_attachment.ssm_core` and `aws_iam_instance_profile.ssm`
# are declared below but almost certainly absent from whatever state file you
# hold, because they were made with the CLI. A plain `apply` will therefore try
# to CREATE them and stop on `EntityAlreadyExists`. Import them first:
#
#   terraform import aws_iam_role.ssm designrepo-ssm
#   terraform import aws_iam_instance_profile.ssm designrepo-ssm
#   terraform import aws_iam_role_policy_attachment.ssm_core \
#     designrepo-ssm/arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore
#
# Then `terraform plan` and read it before applying. The remaining diff should
# be the instance gaining `iam_instance_profile` (an in-place associate, NOT a
# replacement) and the security group losing its SSH rule (in-place). Anything
# proposing to DESTROY the instance, the Elastic IP, the bucket or the IAM user
# is a signal that the state does not match this account — see the note on
# `var.project` in variables.tf for what that costs — and must not be applied.
#
# THE STATE FILE IS THE UNRESOLVED PART OF THIS AND IS WORTH SAYING PLAINLY. It
# is not committed (correctly — it holds the media user's secret key), there is
# no remote backend configured, and so nothing in this repository can tell you
# whether the copy you are holding is current. Until this moves to an S3 backend
# with locking, `terraform plan` output IS the reconciliation step, and reading
# it is not optional.
###############################################################################

terraform {
  required_version = ">= 1.5.0"
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.0"
    }
  }
}

provider "aws" {
  region = var.aws_region
}

############################# S3 bucket for media #############################

resource "aws_s3_bucket" "media" {
  bucket = var.bucket_name
}

resource "aws_s3_bucket_public_access_block" "media" {
  bucket                  = aws_s3_bucket.media.id
  block_public_acls       = true
  ignore_public_acls      = true
  block_public_policy     = false
  restrict_public_buckets = false
}

# Public read for objects under media/ only; uploads stay private (presigned PUT).
resource "aws_s3_bucket_policy" "media_public_read" {
  bucket     = aws_s3_bucket.media.id
  depends_on = [aws_s3_bucket_public_access_block.media]
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Sid       = "PublicReadMedia"
      Effect    = "Allow"
      Principal = "*"
      Action    = "s3:GetObject"
      Resource  = "${aws_s3_bucket.media.arn}/media/*"
    }]
  })
}

# CORS so the web frontend's presigned PUT/GET work from the browser.
#
# THE LIVE BUCKET SPENT AN UNKNOWN LENGTH OF TIME ALLOWING AN ORIGIN THAT DOES NOT EXIST, AND THAT
# IS THE THING TO KNOW ABOUT THIS BLOCK. On 2026-08-30 every browser upload in production failed with
# "Object storage upload failed: network error". The bucket's applied rule read:
#
#     AllowedOrigins: ["https://design-repository.vercel.app"]
#
# The site is served from `designer-repository.vercel.app`. One word — `design` where it should read
# `designer` — and an origin that matches nothing refuses EVERY method, so the preflight for PUT,
# GET and HEAD alike came back `403 AccessForbidden / CORSResponse: This CORS request is not
# allowed`. Measured both ways before changing anything: the identical OPTIONS request answered 200
# when sent with `Origin: https://design-repository.vercel.app` and 403 with the real one, which is
# what proves the rule itself was sound and only its origin list was wrong.
#
# NOTE THE DIAGNOSTIC TRAP, because it cost a wrong first conclusion here. "PUT and GET are both
# refused, therefore there is no CORS document" is WRONG: an origin mismatch refuses everything too,
# and looks identical from outside. Only reading the applied document — `aws s3api get-bucket-cors`
# — distinguishes "no rule" from "a rule naming the wrong site". Read it before concluding.
#
# THIS IS THE SAME ONE-WORD CONFUSION THIS REPOSITORY HAS ALREADY CORRECTED ONCE SOMEWHERE ELSE.
# `docs/CI.md` §2 records `BACKEND_CORS_ORIGINS` being fixed on 2026-08-23 for exactly the
# `design-repository` / `designer-repository` pair, and `.github/workflows/deploy-backend.yml`
# carries a note about the same list once holding both spellings. The API was corrected then; the
# BUCKET was not, and nothing compares the two, so the second half of the same mistake survived
# another week and only surfaced as an upload outage.
#
# A browser cannot report a blocked preflight to JavaScript — no status, no headers, no body — so
# the product could only say "network error", which is why this looked like anything except
# configuration. `frontend/lib/media.ts`'s failure copy now names both possibilities.
#
# Corrected on the live bucket 2026-08-30 with `aws s3api put-bucket-cors` and re-verified by
# preflight (200 + `access-control-allow-origin` for all three methods). `var.cors_allowed_origins`
# must therefore be set to the SAME pair below when this is next applied through Terraform, or the
# next `terraform apply` will hand the bucket back its broken origin list.
#
# `infra/fix-media-bucket-cors.sh` reapplies it with the AWS CLI and re-runs the proving preflight,
# for the case where Terraform state is not to hand (it is not committed, and this directory has no
# `.tfvars`).
#
# The API's own IAM user cannot repair this: it holds only object-level permissions (see
# `aws_iam_user_policy.media` below), deliberately, so no code path in the product can rewrite a
# bucket policy. That is the right boundary and is why this needed account credentials.
resource "aws_s3_bucket_cors_configuration" "media" {
  bucket = aws_s3_bucket.media.id
  cors_rule {
    allowed_headers = ["*"]
    allowed_methods = ["PUT", "GET", "HEAD"]
    allowed_origins = var.cors_allowed_origins
    expose_headers  = ["ETag"]
    max_age_seconds = 3000
  }
}

####################### IAM user for the API (S3 access) ######################

resource "aws_iam_user" "media" {
  name = "${var.project}-media"
}

resource "aws_iam_user_policy" "media" {
  name = "${var.project}-media-s3"
  user = aws_iam_user.media.name
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect   = "Allow"
      Action   = ["s3:PutObject", "s3:GetObject", "s3:DeleteObject"]
      Resource = "${aws_s3_bucket.media.arn}/*"
    }]
  })
}

resource "aws_iam_access_key" "media" {
  user = aws_iam_user.media.name
}

############################### EC2 (API server) ##############################

data "aws_ami" "ubuntu" {
  most_recent = true
  owners      = ["099720109477"] # Canonical
  filter {
    name   = "name"
    values = ["ubuntu/images/hvm-ssd-gp3/ubuntu-noble-24.04-amd64-server-*"]
  }
  filter {
    name   = "virtualization-type"
    values = ["hvm"]
  }
}

# PORT 22 IS CLOSED ON THIS DEPLOYMENT AND THE `ingress` BLOCK FOR IT IS GONE. THAT IS THE CHANGE
# TO UNDERSTAND HERE, BECAUSE IT LOOKS LIKE A DELETION AND IS ACTUALLY A REPLACEMENT.
#
# WHAT IT USED TO SAY, AND WHY THAT WAS A PROBLEM. A single unconditional rule allowed SSH from
# `var.ssh_ingress_cidr`, and the box was provisioned with the operator's home address of the day
# (`49.47.131.192/32`). That makes deployability a property of WHERE A PERSON IS STANDING: the
# operator travels, the address changes, and the way it fails is a red mark nobody reads as "you
# moved". `.github/workflows/deploy-backend.yml` documents the same defect from the other side —
# every scheduled deploy died at `ssh-keyscan` because GitHub's runners are in Azure ranges, so that
# workflow had never once succeeded and the box had only ever been updated by hand.
#
# WHAT REPLACED IT, ON 2026-08-30. `AWS-SSM Session Manager`, through the instance profile declared
# below. `aws ssm start-session --target i-0e091ca8e6b417b52 --region ap-south-1` opens a shell on
# the box from anywhere on earth with no inbound port at all: the agent dials OUT to the SSM
# endpoint, so there is nothing to allow-list and nothing to leave open. Access is IAM-authorised
# rather than key-authorised, every session is a CloudTrail event with a name against it, and
# revoking somebody is an IAM change rather than a scramble to find which authorized_keys files
# hold their public key. That is strictly more secure than a pinned /32 AND strictly more portable
# than one, which is the rare case where the safer option is also the more convenient one.
#
# THE REQUIREMENT THIS SATISFIED was "allow for deployments from anywhere, including GitHub and this
# device, and I travel a lot, so that should not be a boundation, get a way to securely login over
# there as well". Opening 22 to `0.0.0.0/0` would have satisfied the letter of it and is what the
# words most directly suggest; it trades a travel problem for a permanently exposed SSH port on a
# box holding the production API's credentials, and was refused for that reason.
#
# CI STILL USES SSH, AND STILL WORKS, WHICH IS WHY NOTHING HAD TO CHANGE IN THE WORKFLOW. The deploy
# job opens a /32 for its own runner with `ec2:AuthorizeSecurityGroupIngress` and revokes it in an
# `if: always()` step, using the narrowly-scoped `designrepo-ci-sg` IAM user. That rule is
# transient by construction, so Terraform never sees it in a plan and never fights it — but a
# `terraform apply` that lands DURING a deploy would revoke the window early and fail that run.
# Inline `ingress` blocks are authoritative over the whole group; that is the trade for keeping the
# rules readable in one place, and the mitigation is not to apply while a deploy is running.
#
# THE VARIABLE IS KEPT AND DEFAULTS TO EMPTY rather than being deleted. Deleting it would break
# every existing `terraform apply` command line and every runbook that still passes it — Terraform
# refuses a value for an undeclared variable — and, more importantly, reopening SSH is a thing an
# operator may genuinely need to do in an emergency where SSM itself is the thing that is broken.
# Passing `-var="ssh_ingress_cidr=YOUR.IP/32"` brings the rule back for exactly as long as it is
# passed. Empty means closed, and empty is the default, so the safe state is the one you get by
# doing nothing.
resource "aws_security_group" "api" {
  name        = "${var.project}-api"
  description = "Design Prototype Workshop API: SSH (restricted) + HTTP/HTTPS via nginx"

  dynamic "ingress" {
    # A LIST OF ZERO OR ONE, WHICH IS THE WHOLE MECHANISM. Empty `ssh_ingress_cidr` produces no
    # block at all and the group has no port-22 rule — matching the live group exactly, so this
    # reconciliation is a no-op plan rather than a change. `aws_security_group_rule` resources were
    # considered for this and rejected: mixing them with inline blocks makes the two fight over
    # ownership of the same group on every apply, which is a documented Terraform footgun.
    for_each = trimspace(var.ssh_ingress_cidr) == "" ? [] : [trimspace(var.ssh_ingress_cidr)]
    content {
      description = "SSH — deliberately absent unless ssh_ingress_cidr is passed; SSM is the way in"
      from_port   = 22
      to_port     = 22
      protocol    = "tcp"
      cidr_blocks = [ingress.value]
    }
  }
  ingress {
    description = "HTTP (nginx)"
    from_port   = 80
    to_port     = 80
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }
  ingress {
    description = "HTTPS (nginx)"
    from_port   = 443
    to_port     = 443
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }
  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }
}

######################## SSM: how a human gets on the box ######################
#
# THE INSTANCE PROFILE THAT REPLACED INBOUND SSH. Created against the live account on 2026-08-30
# and declared here afterwards, so it needs `terraform import` before the first apply — the header
# of this file carries the exact commands. It mirrors `fieldrepo-ssm`, which the sibling deployment
# already had; this account now has one per project rather than one project reaching for the
# other's role, because an instance profile is the identity of a machine and sharing one would let
# either box assume whatever the other's role is ever granted.
#
# `AmazonSSMManagedInstanceCore` IS THE WHOLE POLICY, AND NOTHING IS ADDED TO IT. It permits the
# agent to register with Systems Manager, receive commands and open sessions — no S3, no EC2, no
# IAM. The API's own S3 credentials stay in `aws_iam_user.media` below and are handed to the
# process through `.env`, deliberately NOT rolled into this role: an instance profile grants its
# permissions to every process on the box, so folding the media keys in here would hand them to
# anything that can reach the metadata endpoint, which includes any future SSRF in the API itself.
# Two identities with two jobs is worth the extra resource.
#
# THE ROLE IS NOT A DEPLOY CREDENTIAL. GitHub Actions still authenticates as `designrepo-ci-sg`
# with its own narrow key pair; this role is only ever assumed BY THE BOX, which is what the trust
# policy below says and the only thing it says.
resource "aws_iam_role" "ssm" {
  name = "${var.project}-ssm"

  # ec2.amazonaws.com and nothing else. No external id, no account principal, no wildcard: the only
  # thing that may wear this identity is an EC2 instance this profile is attached to.
  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "ec2.amazonaws.com" }
      Action    = "sts:AssumeRole"
    }]
  })

  tags = {
    Name    = "${var.project}-ssm"
    Project = var.project
  }
}

resource "aws_iam_role_policy_attachment" "ssm_core" {
  role       = aws_iam_role.ssm.name
  policy_arn = "arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore"
}

resource "aws_iam_instance_profile" "ssm" {
  name = "${var.project}-ssm"
  role = aws_iam_role.ssm.name
}

resource "aws_instance" "api" {
  ami           = data.aws_ami.ubuntu.id
  instance_type = "t3.micro"

  # KEPT, THOUGH PORT 22 IS CLOSED, AND KEPT DELIBERATELY. `key_name` is ForceNew: removing it from
  # this configuration does not detach a key pair, it DESTROYS AND RECREATES the instance — which on
  # this deployment means a new box with no `/home/ubuntu/app`, no `.env`, no nginx certificate and
  # a new address behind the Elastic IP, to tidy up an attribute that costs nothing while it sits
  # there unused. The key is also what CI's transient deploy window authenticates with, so it is not
  # unused at all; it is unusable only while the port is shut, which is most of the time.
  key_name               = var.ssh_key_name
  vpc_security_group_ids = [aws_security_group.api.id]

  # THE SSM PROFILE. Attaching one is an in-place update on a running instance — no replacement, no
  # downtime, no reboot — which is why this could be done on a box serving production traffic and
  # why `deploy-backend.yml` was wrong to call it "a migration, not a workflow edit". The agent ships
  # in the Ubuntu 24.04 AMI already running; it simply had no credentials to register with until the
  # profile arrived, and it picked them up on its own within a minute of the attach.
  iam_instance_profile = aws_iam_instance_profile.ssm.name

  user_data = file("${path.module}/user_data.sh")

  root_block_device {
    volume_size = 30
    volume_type = "gp3"
  }

  tags = {
    Name    = "${var.project}-api"
    Project = var.project
  }
}

resource "aws_eip" "api" {
  instance = aws_instance.api.id
  domain   = "vpc"
  tags     = { Name = "${var.project}-api" }
}

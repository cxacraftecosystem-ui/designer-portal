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
# ─── AND SINCE 2026-09-03 THE DRIFT ALSO RUNS THE OTHER WAY ──────────────────
# Five things are now declared here that DO NOT MATCH THE ACCOUNT YET, because
# they were written as part of an audit and deliberately not applied:
#
#   a. `aws_s3_bucket_lifecycle_configuration.media` — thirty-day expiry scoped
#      to the `backups/` prefix that the new nightly pg_dump workflow writes to.
#   b. `metadata_options` on `aws_instance.api` — an in-place modify, no reboot.
#   c. the `backend "s3"` block — not a resource at all; it changes where state
#      lives and needs `terraform init -migrate-state`, not `apply`.
#   d. `aws_iam_user_policy.media` split by prefix — the live inline policy is
#      still Put/Get/DeleteObject on `${bucket}/*`, which (a) and (c) turn into
#      "the API's key can delete the backups and read the state". An in-place
#      `PutUserPolicy` on the same policy name: NO KEY ROTATION, nothing to
#      re-paste into BACKEND_ENV or the Actions secrets. Read the essay above
#      that resource before applying, and apply it in the same afternoon as (c).
#   e. `lifecycle { ignore_changes = [user_data] }` on `aws_instance.api`. Not a
#      change to the account at all — it changes what the PLAN says, and it
#      SUBTRACTS: see the note on the block itself.
#
# So the first plan run after this change proposes (a) as a CREATE, and (b) and
# (d) as in-place UPDATEs. All three are expected. Anything else in that plan is
# drift nobody has written down yet, and is the thing to investigate.
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
# THE STATE FILE WAS THE UNRESOLVED PART OF THIS, AND ON 2026-09-03 A REMOTE
# BACKEND WAS DECLARED FOR IT. The paragraph that stood here said: "it is not
# committed (correctly — it holds the media user's secret key), there is no
# remote backend configured, and so nothing in this repository can tell you
# whether the copy you are holding is current. Until this moves to an S3 backend
# with locking, `terraform plan` output IS the reconciliation step." Every word
# of that was true of the configuration as it stood, and the `backend "s3"` block
# below is what changes it — but ONLY ONCE SOMEBODY RUNS THE MIGRATION. Until
# then the sentence still describes reality, so it is corrected rather than
# deleted, and the migration is written out beside the block itself.
#
# READ THIS ORDER BEFORE TOUCHING ANYTHING, because the two migrations interact:
# the state must be moved to S3 FIRST, from the machine holding the freshest
# copy, and the SSM imports above run against that migrated state afterwards.
# Doing it the other way round imports into a local state file that the next
# `init` then asks to overwrite.
###############################################################################

terraform {
  # ─── 1.10, NOT 1.5, AND THE BUMP IS LOAD-BEARING ───────────────────────────
  # `use_lockfile` in the backend below is S3-native state locking, which landed
  # in Terraform 1.10 and replaces the old DynamoDB lock table. On 1.5 this
  # configuration does not merely lose locking — `terraform init` rejects the
  # unknown argument, which is the right failure: a version that silently ran
  # without locking would be the one case this block exists to prevent.
  required_version = ">= 1.10.0"

  # ─── THE REMOTE STATE, AND WHY IT LIVES IN THE MEDIA BUCKET ────────────────
  #
  # WHAT IT FIXES. There is exactly one copy of this state and it is on somebody's
  # laptop, uncommitted (correctly — see below). Nothing can tell you whether the
  # copy you hold is the current one, two people cannot apply without one of them
  # silently reverting the other, and the day that laptop dies the account
  # becomes un-managed infrastructure that has to be re-imported resource by
  # resource. This repository has already lost a disk once.
  #
  # WHY THIS BUCKET RATHER THAN A NEW ONE. It already exists, it is already in
  # the same region and account, and adding a bucket means adding a bootstrap
  # problem (something has to create the bucket that holds the state that
  # describes the bucket). The prefix keeps them apart.
  #
  # THE STATE CONTAINS A SECRET, AND TWO SEPARATE GRANTS HAVE TO MISS IT.
  # `aws_iam_access_key.media` is a generated credential, so its secret is IN THE
  # STATE FILE in plaintext — that is why terraform.tfstate is gitignored and why
  # it must never be readable by anything but an operator. Two things could reach
  # this prefix, and the answer is different for each:
  #
  #   1. THE ANONYMOUS GRANT. `aws_s3_bucket_policy.media_public_read` below
  #      allows `s3:GetObject` to `Principal = "*"` on `${bucket}/media/*` AND
  #      NOTHING ELSE, so `tfstate/` and `backups/` fall outside it. IF THAT
  #      POLICY IS EVER WIDENED TO `/*`, THIS STATE FILE AND EVERY DATABASE
  #      BACKUP BECOME WORLD-READABLE. Do not widen it.
  #   2. THE API'S OWN CREDENTIAL — the one that lives on an internet-facing box.
  #      This paragraph used to stop at (1), and that was the gap: until
  #      2026-09-03 `aws_iam_user_policy.media` allowed Put/Get/DeleteObject on
  #      `${bucket}/*`, so putting the state here would have handed the API's key
  #      read access to the file holding that key's own secret, and delete rights
  #      over every backup beside it. It is now split by prefix (the essay above
  #      that resource has the whole argument) and names `tfstate/` in no
  #      statement at all: Terraform runs under an operator's own credentials,
  #      never under that user.
  #
  # SO THE PREFIX IS NOT THE MECHANISM — IT IS WHAT THE MECHANISM IS WRITTEN IN
  # TERMS OF. A prefix is private only while every policy that can name this
  # bucket declines to name it, which means adding a third prefix here is a
  # change to both policies and not just to this comment. (The same argument is
  # made from the other side in .github/workflows/backup-db.yml.)
  #
  # THE MIGRATION, IN ORDER, FROM THE MACHINE HOLDING THE FRESHEST STATE:
  #
  #   1. Prove you have the right copy first. `terraform plan` against the LOCAL
  #      state should be a no-op except for the known SSM drift described above.
  #      A plan proposing to destroy the instance, the EIP, the bucket or the IAM
  #      user means this is the WRONG state file and migrating it would publish
  #      that wrongness to everybody. Stop there.
  #   2. cp terraform.tfstate terraform.tfstate.pre-s3-backup   (keep it offline)
  #   3. terraform init -migrate-state
  #      Terraform sees a new backend and offers to copy the existing state up.
  #      Answer yes. It writes s3://designrepo-media-626159998512/tfstate/…
  #   4. Then the imports the header above lists (aws_iam_role.ssm,
  #      aws_iam_instance_profile.ssm, aws_iam_role_policy_attachment.ssm_core).
  #      They must run AFTER the migration or they land in the local state that
  #      is about to be superseded.
  #   5. terraform plan — and it must come back a NO-OP apart from what the
  #      header lists: (a) as a create, (b) and (d) as in-place updates. Read it.
  #      Do not apply a plan that proposes a replacement.
  #
  #      THIS ACCEPTANCE TEST USED TO SAY "the two in-place changes" AND WOULD
  #      HAVE FAILED HONESTLY, because a third diff was always going to be in
  #      that plan: `user_data`. The live box was launched with an older boot
  #      script, `infra/terraform/user_data.sh` has been edited since, and a
  #      `user_data` diff on an existing instance is applied by STOPPING the
  #      instance, modifying the attribute and STARTING it again — an outage on
  #      the production API, proposed as an ordinary in-place update and easy to
  #      wave through. `ignore_changes = [user_data, ami]` (item (e)) is what removes
  #      it, so the plan a reader gets now really is the three lines above. If
  #      you SEE a user_data or ami diff, ONE of two things is true (corrected
  #      2026-09-03, when the second was observed): the block was deleted — or
  #      the resource is being REPLACED, because ignore_changes never applies
  #      to a create and a replacement is one. A replacement row on this
  #      instance is never acceptable in a routine plan; find the ForceNew
  #      attribute it names and pin or ignore that, as `ami` itself had to be.
  #
  # NOTHING IN THIS CHANGE WAS RUN. No `init`, no `plan`, no `apply` — this file
  # only declares the intent, and a declaration that has not been reconciled is
  # exactly the state this header warns about. The commands above are the
  # reconciliation and they are somebody's deliberate afternoon, not a side
  # effect of a merge.
  backend "s3" {
    bucket = "designrepo-media-626159998512"
    key    = "tfstate/designer-portal.tfstate"
    region = "ap-south-1"
    # S3-native locking (Terraform >= 1.10). A `.tflock` object beside the state
    # object, no DynamoDB table to create, pay for or forget to delete. Without
    # it two concurrent applies interleave writes and the loser's resources
    # simply vanish from the record.
    use_lockfile = true
  }

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

# ─── RETENTION FOR THE NIGHTLY DATABASE DUMPS, ADDED 2026-09-03 ────────────────────────────────
#
# `.github/workflows/backup-db.yml` writes one `pg_dump` a night to
# `backups/designer-portal/YYYY-MM-DD.sql.gz` in this bucket. That workflow deliberately CANNOT
# expire them: expiry means listing and deleting, and giving a scheduled job delete rights over the
# bucket that also holds every photograph in the product is a far worse trade than paying for a few
# gigabytes. Expiry is a property of the bucket, so it is declared here.
#
# THIRTY DAYS IS THE NUMBER AND IT IS A JUDGEMENT, NOT A STANDARD. The failure a backup protects
# against on this project is a bad migration, a mistaken bulk delete, or a provider account problem
# — all of which are noticed in hours or days, not months. A month of daily copies is far more than
# that needs, and roughly 30 × the compressed size of one dump in standing cost. If a retention
# obligation is ever placed on this ministry data, this number is the one to change, and it is the
# only place to change it.
#
# THE FILTER IS THE WHOLE SAFETY PROPERTY. `prefix = "backups/"` scopes the rule to the dumps.
# Without a filter — or with an empty one — this rule expires EVERY OBJECT IN THE BUCKET after
# thirty days, which would silently delete every photograph, signature and attachment the product
# has ever stored. There is no undo for that and no versioning on this bucket to fall back on. Any
# edit to this resource must be read with that sentence in mind.
#
# `abort_incomplete_multipart_upload` is here because `aws s3 cp` uses multipart for larger objects
# and a run killed mid-upload leaves parts that are invisible to `ls` and billable forever.
resource "aws_s3_bucket_lifecycle_configuration" "media" {
  bucket = aws_s3_bucket.media.id

  rule {
    id     = "expire-database-backups"
    status = "Enabled"

    filter {
      prefix = "backups/"
    }

    expiration {
      days = 30
    }

    abort_incomplete_multipart_upload {
      days_after_initiation = 7
    }
  }
}

####################### IAM user for the API (S3 access) ######################

resource "aws_iam_user" "media" {
  name = "${var.project}-media"
}

# ─── SPLIT BY PREFIX ON 2026-09-03, BECAUSE THIS BUCKET STOPPED BEING ONLY MEDIA ────────────────
#
# WHAT IT SAID BEFORE, AND HOW IT BECAME WRONG WITHOUT ANYBODY EDITING IT. One statement:
# `s3:PutObject`, `s3:GetObject`, `s3:DeleteObject` on `${bucket}/*` — the WHOLE bucket. That was
# defensible for as long as the bucket held nothing but user media, and the same 2026-09-03 wave
# moved two other things into it: the nightly `pg_dump` under `backups/` and this configuration's
# own Terraform state under `tfstate/`. On the day those land, the unsplit policy quietly means THE
# API'S CREDENTIAL CAN READ AND DELETE EVERY DATABASE BACKUP AND CAN READ THE STATE FILE THAT
# CONTAINS ITS OWN SECRET KEY. Nobody widened anything; the blast radius of an unchanged wildcard
# grew because new things were put inside it, which is the way this kind of hole is usually made.
#
# THAT CREDENTIAL IS NOT A HARD ONE TO GET AT, WHICH IS WHAT MAKES THIS WORTH THE WORDS. It sits in
# `BACKEND_ENV` on an internet-facing t3.micro, in `.env` on that box's disk, and in the environment
# of a process whose day job is parsing uploaded media; the SAME key pair is also handed to
# `.github/workflows/backup-db.yml` as `AWS_MEDIA_ACCESS_KEY_ID`/`AWS_MEDIA_SECRET_ACCESS_KEY`. An
# SSRF or an RCE in the API was therefore not "somebody can scribble on photographs" but "somebody
# can delete every backup this project has" — with no versioning on this bucket to undo it, and no
# alarm that fires until a restore is attempted.
#
# STATEMENT 1 IS THE APP'S ACTUAL SURFACE, MEASURED RATHER THAN ASSUMED.
# `backend/app/services/s3.py:make_object_key` is the ONLY thing in the product that mints an object
# key and it returns `media/<user_id>/<uuid>/<filename>`. Every stored `objectKey` comes from it —
# including the Android APK, which `app_release` records after the publishing device uploads it
# through `POST /media/presign`. Both delete paths refuse a key outside the caller's own prefix
# (`routes/media.py`'s `media/<user_id>/` guards on the multipart and object-delete routes), and
# nothing in the backend calls `list_objects` at all. So `media/*` is not a restriction placed on
# the app; it is a description of the app.
#
# THE ONE PRE-APPLY CHECK WORTH A MINUTE, because the code above describes the code and not the
# bucket: an object put there BY HAND, before the convention or by somebody debugging, would stop
# being readable the moment this lands. With operator credentials —
#   aws s3 ls s3://designrepo-media-626159998512/ --recursive | grep -Ev '^\S+ +\S+ +[0-9]+ (media|backups|tfstate)/'
# — anything that prints is a key no policy statement below covers. Expect no output.
#
# `s3:AbortMultipartUpload` IS NEW HERE AND CLOSES AN EXISTING BUG rather than widening anything.
# The old statement listed three actions and that is not one of them, so `abort_multipart_upload`
# (`services/s3.py`, called by the multipart-abort route) has been answering AccessDenied for as
# long as it has existed — leaving the parts of every cancelled upload on the bucket, billable and
# invisible to `aws s3 ls`. It is a write-side action, on `media/*` only, and grants no read of
# anything. If an operator would rather keep the delta to a pure narrowing, dropping this one line
# restores the previous behaviour exactly, bug included.
#
# STATEMENT 2 IS A DROP-BOX: `s3:PutObject` AND NOTHING ELSE, ON `backups/*`. The backup workflow
# uploads a dump and then proves it landed; it does not need to read one back, and an identity that
# could read one is an identity that can read every row in the database. So this cannot fetch
# yesterday's dump, cannot delete it, and cannot overwrite it with anything but today's dump under
# today's date. Restoring is an operator action with operator credentials, which is the right shape
# for the one operation that is only ever performed deliberately.
#
# WHAT THE DROP-BOX COSTS, SAID PLAINLY: an interrupted `aws s3 cp` of a large dump cannot abort its
# own multipart upload here, because `s3:AbortMultipartUpload` on `backups/*` would let a compromised
# CI job discard a backup mid-flight — which is most of what delete rights buy an attacker anyway.
# The orphaned parts are collected by `abort_incomplete_multipart_upload` in the lifecycle rule
# above, which is part of why that block exists. Seven days of billable parts is a cost problem; a
# readable backup is a disclosure one.
#
# STATEMENT 3 IS THE VERIFICATION GRANT, AND IT IS THE TRADE WORTH READING BEFORE CHANGING.
# `backup-db.yml`'s last step exists to make its green tick mean something: it re-reads the object
# after the upload, because `aws s3 cp` reports what it SENT and not what is readable afterwards.
# It used to do that with `s3api head-object`, which requires `s3:GetObject` ON THE KEY — i.e. the
# proof step is what would have forced a backup-reading grant onto a credential that lives on a
# public-facing box. The workflow's assert now uses `s3api list-objects-v2`, which requires
# `s3:ListBucket` on the BUCKET and answers with Key and Size. Size is the entire thing the assert
# ever wanted; the bytes never were.
#
# The `s3:prefix` condition is what stops that being a licence to browse. The request's prefix must
# start with `backups/`, so this identity cannot enumerate `media/`, cannot enumerate `tfstate/`,
# and cannot list the bucket root — a listing without a matching prefix is refused outright rather
# than silently filtered. What it learns is the names and sizes of dumps it wrote itself. S3 LIST
# has been strongly read-after-write consistent since 2020, so an object PUT a second earlier is in
# the listing: this is not a weaker check than the HEAD it replaces, only a cheaper-to-grant one.
# The alternative — keep head-object, add `s3:GetObject` scoped to `backups/*` — is one line shorter
# and hands read access to every database dump to the API box's key. Rejected on that basis. A
# future step that genuinely must READ a dump from CI should get its own identity, not this one.
#
# NOTHING HERE NAMES `tfstate/`, AND NOTHING MAY. Terraform runs under a human operator's own
# credentials, never under this user. The state holds `aws_iam_access_key.media`'s secret in
# plaintext plus every attribute of the account's infrastructure, and the write side is worse than
# the read side: a compromised API that could PUT there could rewrite the state so that the next
# `apply` destroys or re-owns resources. Do not add a `tfstate/` statement to this policy for any
# reason, including "the apply failed and this was quicker".
#
# APPLYING THIS IS AN IN-PLACE POLICY UPDATE AND NEEDS NO KEY ROTATION. `aws_iam_user_policy` is a
# `PutUserPolicy` against the same inline policy name on the same user, so there is no new
# credential, nothing to re-paste into `BACKEND_ENV`, and nothing to update in this repository's
# Actions secrets. The moment it lands the running API keeps working (every key it touches is under
# `media/`) and the backup job keeps working (statements 2 and 3). It is an in-place UPDATE in the
# plan, and it is one of the changes the file header lists as expected.
#
# UNTIL IT IS APPLIED, THE NIGHTLY BACKUP'S PROOF STEP IS RED, and that is the intended shape of the
# gap rather than a surprise. `backup-db.yml` now verifies with `list-objects-v2`, which needs the
# `s3:ListBucket` in statement 3; the live policy grants none, so the dump uploads (PutObject is
# covered by the old wildcard too) and the verification fails AccessDenied. A backup whose proof is
# red is the honest state of a backup nobody has proved. Applying this closes it in one command.
resource "aws_iam_user_policy" "media" {
  name = "${var.project}-media-s3"
  user = aws_iam_user.media.name
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid    = "AppMediaObjects"
        Effect = "Allow"
        Action = [
          "s3:PutObject",
          "s3:GetObject",
          "s3:DeleteObject",
          "s3:AbortMultipartUpload"
        ]
        Resource = "${aws_s3_bucket.media.arn}/media/*"
      },
      {
        Sid      = "BackupWriteOnlyDropBox"
        Effect   = "Allow"
        Action   = "s3:PutObject"
        Resource = "${aws_s3_bucket.media.arn}/backups/*"
      },
      {
        Sid       = "BackupVerifyByListingOnly"
        Effect    = "Allow"
        Action    = "s3:ListBucket"
        Resource  = aws_s3_bucket.media.arn
        Condition = {
          StringLike = {
            "s3:prefix" = ["backups/*"]
          }
        }
      }
    ]
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

  # The live role was created by CLI on 2026-08-30 carrying this description; declaring it here
  # keeps the first reconciled apply from silently clearing a sentence somebody wrote on purpose
  # (2026-09-03). The tags below are additive on that same apply — the live role has none.
  description = "SSM Session Manager access for ${var.project}-api. Mirrors fieldrepo-ssm."

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

  # ─── IMDSv2, PINNED 2026-09-03 — AND THE AUDIT THAT PROMPTED IT WAS WRONG ────────────────────
  #
  # SAY THE REFUTATION FIRST, so nobody reads this block as a closed hole. An audit reported the
  # instance metadata service as exposed to SSRF because `metadata_options` was absent. It is not:
  # Canonical's Ubuntu 24.04 AMIs are published with `ImdsSupport: v2.0`, so an instance launched
  # from `data.aws_ami.ubuntu` above defaults to `HttpTokens = required` already. The finding was
  # checked against the AMI rather than against the absence of a block, and it did not survive that.
  #
  # SO WHY WRITE IT DOWN. Because "it defaults correctly" is a property of THE IMAGE, not of this
  # configuration, and the image is selected by `most_recent = true` over a name pattern — the
  # instance's metadata posture is therefore decided by whichever AMI Canonical published most
  # recently, which is not a thing this repository controls or reviews. Pinning it makes the
  # posture a property of the configuration: it cannot regress, and a reader does not have to know
  # the AMI's defaults to know the answer.
  #
  # `http_put_response_hop_limit = 1` IS THE HALF THAT ACTUALLY CHANGES SOMETHING. AWS's default is
  # 2, which exists so a container on the instance can still reach the metadata endpoint through the
  # docker bridge. Nothing on this box runs in a container — uvicorn and the queue worker are plain
  # systemd units — so a hop limit of 1 costs nothing and stops the credentials being reachable from
  # inside a container if one is ever added without thought. The instance profile it protects is
  # `designrepo-ssm`, which carries `AmazonSSMManagedInstanceCore`: enough to open a shell on this
  # box, which is enough.
  #
  # APPLYING THIS IS AN IN-PLACE MODIFY, NOT A REPLACEMENT — `ModifyInstanceMetadataOptions` on a
  # running instance, no reboot. Verify with:
  #   aws ec2 describe-instances --instance-ids i-0e091ca8e6b417b52 \
  #     --query 'Reservations[].Instances[].MetadataOptions'
  metadata_options {
    http_endpoint               = "enabled"
    http_tokens                 = "required"
    http_put_response_hop_limit = 1
  }

  # FIRST BOOT ONLY. This attribute is read once, by cloud-init, on a machine that does not exist
  # yet; the live box's copy is frozen at whatever was passed the day it was launched. The
  # `lifecycle` block at the bottom of this resource is what keeps editing the file from becoming an
  # outage, and is the thing to read before changing this line.
  user_data = file("${path.module}/user_data.sh")

  root_block_device {
    volume_size = 30
    volume_type = "gp3"
  }

  tags = {
    Name    = "${var.project}-api"
    Project = var.project
  }

  # ─── WHY TERRAFORM MUST NOT SEE user_data DRIFT, ADDED 2026-09-03 ───────────────────────────
  #
  # WHAT HAPPENS WITHOUT THIS BLOCK. `user_data.sh` was rewritten in this wave for the release
  # layout (`/home/ubuntu/app/current/backend` in place of `/home/ubuntu/app/backend`). The live
  # instance was launched long before that, so its stored `user_data` is the OLD script and every
  # `terraform plan` from now until the box is replaced carries a `user_data` diff. Applying it does
  # not re-run anything: cloud-init consumed user_data at first boot and never looks again. What it
  # DOES do is `StopInstances` / `ModifyInstanceAttribute` / `StartInstances` — because the attribute
  # is only writable on a stopped instance. So the plan reads as a harmless in-place update and the
  # apply is a production outage for the length of a stop/start, with a new public IP for anything
  # not behind the Elastic IP, for the sole purpose of updating a script that will never execute.
  # That is a bad trade offered in a form that looks like a good one, which is the kind worth
  # blocking rather than remembering.
  #
  # THE DRIFT IS EXPECTED, NOT AN ERROR, AND THAT IS THE WHOLE JUSTIFICATION. The live box is
  # brought to the current configuration by the "Prepare the release layout and the systemd units"
  # step in `.github/workflows/deploy-backend.yml`, which installs the same paths and limits as
  # systemd drop-ins on every deploy. The base units on disk are therefore ALLOWED to be older than
  # this file: the drop-ins are merged over them. So `user_data` disagreeing with the live instance
  # is the designed state, and Terraform reporting it as a change to correct is Terraform reporting
  # the design as a defect.
  #
  # THE FILE IS STILL KEPT CURRENT, FOR REBUILDS. `ignore_changes` suppresses the diff on an
  # EXISTING instance; it does not touch what a NEW one is created with. An operator who replaces
  # this box — or builds a second one — gets whatever `user_data.sh` says at that moment, which is
  # why user_data.sh and the deploy workflow's drop-ins are required to stay in agreement (both
  # files say so, at length). Nothing here makes the script optional; it makes editing it free.
  #
  # TO DELIBERATELY PUSH A NEW BOOT SCRIPT ONTO THE RUNNING BOX, which is a thing almost nobody
  # should want: remove this block for one apply, accept the stop/start in a window, put it back.
  # `terraform apply -replace=aws_instance.api` is the other way and it is a rebuild, with
  # everything that implies (see the note on `key_name` above).
  #
  # `ami` JOINED THE LIST ON 2026-09-03, AND THE FIRST RECONCILIATION PLAN IS WHY. `data.aws_ami`
  # asks for Canonical's MOST RECENT noble image, so every Canonical publish makes the data source
  # answer a new id — and `ami` is ForceNew, so the very first plan after the state was rebuilt
  # proposed DESTROYING the production instance to chase a fortnight-newer base image. The live
  # box's identity is managed by the deploy workflow, not by rebuild-time inputs; same argument as
  # `user_data`, same shape: an EXISTING instance keeps its image, and a deliberate rebuild (or a
  # second box) still gets the current one, because ignore_changes never touches a create.
  lifecycle {
    ignore_changes = [user_data, ami]
  }
}

resource "aws_eip" "api" {
  instance = aws_instance.api.id
  domain   = "vpc"
  tags     = { Name = "${var.project}-api" }
}

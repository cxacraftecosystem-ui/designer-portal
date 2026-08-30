#!/usr/bin/env bash
#
# APPLY THE MEDIA BUCKET'S CORS RULE. Run this once, with AWS credentials that can administer the
# bucket, and every browser upload starts working again.
#
# ─── WHAT THIS FIXES, AND HOW IT WAS DIAGNOSED ────────────────────────────────────────────────
#
# Symptom, reported 2026-08-30: every file upload from the web client fails with
#
#     Upload failed — Object storage upload failed: network error
#
# That sentence comes from `frontend/lib/media.ts`'s bare `xhr.onerror` arm, and it is the ONLY
# thing a browser will tell JavaScript when a cross-origin request is refused: no status, no
# headers, no body. A blocked preflight and a pulled network cable are indistinguishable from
# inside the page, which is why the message names a network error for what is actually a bucket
# configuration problem.
#
# THE CAUSE, MEASURED RATHER THAN INFERRED — AND IT IS A ONE-WORD TYPO IN AN ORIGIN.
#
# The bucket's applied rule (read with `aws s3api get-bucket-cors`) said:
#
#     AllowedOrigins: ["https://design-repository.vercel.app"]
#
# The site is served from `designer-repository.vercel.app`. `design` where it should read
# `designer`. An origin that matches nothing refuses EVERY method, so PUT, GET and HEAD alike came
# back `403 AccessForbidden / CORSResponse: This CORS request is not allowed`.
#
# The decisive measurement, one request each way against the same object:
#
#     Origin: https://design-repository.vercel.app   -> 200, access-control-allow-origin echoed
#     Origin: https://designer-repository.vercel.app -> 403 AccessForbidden
#
# Same bucket, same method, same headers. That is what proves the RULE was sound and only its
# ORIGIN LIST was wrong — as opposed to the rule being absent or missing a method.
#
# ⚠ THE DIAGNOSTIC TRAP, WRITTEN DOWN BECAUSE IT PRODUCED A WRONG FIRST CONCLUSION HERE. Seeing PUT
# *and* GET both refused, it is natural to reason "a rule that merely omitted PUT would still have
# answered the GET preflight, therefore there is no rule at all". That inference is wrong: an origin
# mismatch also refuses every method, and from outside the two are indistinguishable. Only reading
# the applied document tells you which you have. Run `get-bucket-cors` BEFORE concluding anything.
#
# ⚠ THIS IS THE SECOND HALF OF A MISTAKE THIS REPOSITORY ALREADY FIXED ONCE. `docs/CI.md` §2 records
# `BACKEND_CORS_ORIGINS` being corrected on 2026-08-23 for exactly the `design-repository` /
# `designer-repository` pair, and `deploy-backend.yml` notes that list once holding both spellings.
# The API was corrected then. The BUCKET was not, nothing compares the two, and so the same typo
# survived in the other enforcement point until it surfaced as an upload outage a week later.
#
# ─── WHY THE APPLICATION CANNOT REPAIR THIS ITSELF ────────────────────────────────────────────
#
# The API's IAM user is deliberately least-privilege: `s3:PutObject`, `s3:GetObject`,
# `s3:DeleteObject` on the objects, and nothing else (`infra/terraform/main.tf:84-95`). It holds no
# `s3:PutBucketCors`, so no endpoint, script or migration running as the backend can fix this — by
# design, and that design is correct. Repairing it needs credentials that can administer the
# bucket, which is a human with account access, which is why this is a script and not a code change.
#
# ─── THE TWO WAYS TO APPLY IT, AND WHICH TO PREFER ────────────────────────────────────────────
#
# PREFER TERRAFORM if the state for this account is reachable, because the declaration is already
# written and applying it keeps the deployment and the repository in agreement:
#
#     cd infra/terraform
#     terraform init
#     terraform apply -target=aws_s3_bucket_cors_configuration.media
#
# USE THIS SCRIPT when the Terraform state is not to hand (it is not committed, and this repository
# has no `.tfvars`), or when the outage needs closing before anybody reconciles state. It applies
# exactly the rule `main.tf` declares, so a later `terraform apply` is a no-op rather than a fight.
#
# ─── RUNNING IT ───────────────────────────────────────────────────────────────────────────────
#
#     AWS_PROFILE=<a profile that can administer the bucket> ./infra/fix-media-bucket-cors.sh
#
# It prints the bucket's CORS rule before and after, and then re-runs the exact preflight that
# proved the fault, so the last thing on screen is evidence the fix worked rather than a claim that
# it did. Nothing here is destructive to any object: `put-bucket-cors` replaces the bucket's CORS
# document and touches no file that has been uploaded.

set -euo pipefail

BUCKET="${BUCKET:-designrepo-media-626159998512}"
REGION="${REGION:-ap-south-1}"

# The origins that must be allowed. These are the two the API itself already trusts —
# `.github/workflows/deploy-backend.yml:268` pins `BACKEND_CORS_ORIGINS` to exactly this pair — so
# the bucket and the API agree about who the web client is, rather than being two lists that drift.
#
# WHY NOT "*", EVEN THOUGH `variables.tf:48` DEFAULTS TO IT. A presigned PUT URL carries its own
# authorisation in the query string, so the object is not protected BY the origin list; but a
# wildcard means any page on the internet that gets hold of a presigned URL can spend it from the
# victim's browser. The named list costs nothing and removes that. Add an origin here when a new
# frontend deployment needs one — and add it to `BACKEND_CORS_ORIGINS` in the same change, or the
# API will refuse the same browser the bucket just started accepting.
read -r -d '' CORS_JSON <<'JSON' || true
{
  "CORSRules": [
    {
      "AllowedHeaders": ["*"],
      "AllowedMethods": ["PUT", "GET", "HEAD"],
      "AllowedOrigins": [
        "https://designer-repository.vercel.app",
        "http://localhost:3000"
      ],
      "ExposeHeaders": ["ETag"],
      "MaxAgeSeconds": 3000
    }
  ]
}
JSON

if ! command -v aws >/dev/null 2>&1; then
  echo "ERROR: the AWS CLI is not on PATH. Install it, or apply the Terraform target instead:" >&2
  echo "  cd infra/terraform && terraform apply -target=aws_s3_bucket_cors_configuration.media" >&2
  exit 1
fi

echo "== Caller identity (confirm this is the right account before continuing) =="
aws sts get-caller-identity

echo
echo "== CORS on ${BUCKET} BEFORE =="
# PRINTED BEFORE THE CHANGE BECAUSE THIS IS THE ONE OUTPUT THAT DISTINGUISHES THE TWO FAULTS, and
# from outside the bucket they look identical (see the diagnostic trap in the header). Read the
# `AllowedOrigins` line here against the site's real host: a WRONG origin is what caused the
# 2026-08-30 outage, and it refuses every method exactly as a MISSING document does. A bucket with
# no document at all answers `NoSuchCORSConfiguration` instead — also not an error for this script's
# purposes. Either way, report and carry on rather than dying on `set -e`.
aws s3api get-bucket-cors --bucket "${BUCKET}" --region "${REGION}" 2>&1 || true

echo
echo "== Applying =="
printf '%s' "${CORS_JSON}" > /tmp/media-cors.json
aws s3api put-bucket-cors \
  --bucket "${BUCKET}" \
  --region "${REGION}" \
  --cors-configuration file:///tmp/media-cors.json
rm -f /tmp/media-cors.json
echo "applied."

echo
echo "== CORS on ${BUCKET} AFTER =="
aws s3api get-bucket-cors --bucket "${BUCKET}" --region "${REGION}"

echo
echo "== Proof: the preflight that was failing =="
# S3 applies a new CORS document within seconds, but this is the check that actually decides whether
# uploads work, so it is run here rather than left to somebody to remember. A 200 with an
# `access-control-allow-origin` header is the pass; a 403 AccessForbidden means it did not take.
for METHOD in PUT GET; do
  printf -- '--- %s ---\n' "${METHOD}"
  curl -s -i -X OPTIONS \
    "https://${BUCKET}.s3.dualstack.${REGION}.amazonaws.com/media/preflight-probe" \
    -H "Origin: https://designer-repository.vercel.app" \
    -H "Access-Control-Request-Method: ${METHOD}" \
    -H "Access-Control-Request-Headers: content-type" \
    | sed -n '1p;/[Aa]ccess-[Cc]ontrol/p'
done

echo
echo "If both blocks above show '200' and an access-control-allow-origin header, uploads work again."
echo "If either still shows 403 AccessForbidden, the put-bucket-cors call did not reach this bucket —"
echo "check the caller identity printed at the top against the bucket's owning account."

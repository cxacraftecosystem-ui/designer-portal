#!/usr/bin/env bash

set -euo pipefail

BUCKET="${BUCKET:-designrepo-media-626159998512}"
REGION="${REGION:-ap-south-1}"

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

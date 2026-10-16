# API origin lock: the box answers CloudFront and nothing else

Done on 2026-10-17, live, with the owner's go-ahead ("do everything that is required for both the
servers, do it now"). The sibling documentation portal was locked the same way the same day.

## Why

The API box sat on the open internet: port 80 (and 443, on which nothing listens) open to
`0.0.0.0/0`. Anyone who knew the address could skip CloudFront and talk to nginx directly, and
then put whatever they liked in `X-Forwarded-For`. That makes the client-address hop count in
`backend/app/scale/rate_limit.py` (`FORWARDED_CLIENT_HOPS`) a guess about who sent the request.
With the box reachable only through this portal's CloudFront distribution, the chain is fixed:

```
browser / app  ->  CloudFront (appends the viewer's address)  ->  nginx (appends the CloudFront edge)  ->  uvicorn
```

so the client is always the **2nd entry from the right**, and `FORWARDED_CLIENT_HOPS=2` is exact.

## What is in place

Two independent locks; either one alone would stop a direct caller.

| Lock | Where | What it does |
|---|---|---|
| Security group | `sg-0e379f2831c851008` (`cloudfront-origin-only-designer`) | Port 80 only from the AWS-managed prefix list `pl-9aa247f3` (`com.amazonaws.global.cloudfront.origin-facing`). That list weighs about 55 rules against the 60-per-group quota, which is why it has a group of its own. |
| Origin header | CloudFront `E2XKM13U9R3Q6`, origin `designrepo-ec2-origin` | Sends `X-Origin-Verify: <secret>` on every request to the box. |
| nginx check | `/etc/nginx/snippets/origin-verify.conf`, included by the `fieldrepo` site | 403 unless `X-Origin-Verify` matches; loopback (`127.0.0.1`) is let through for on-box checks. |

The prefix list on its own would still admit *somebody else's* CloudFront distribution pointed at
this address. The header is what makes it this portal's distribution.

**The secret** is the Actions secret `ORIGIN_VERIFY_SECRET` (48 letters and digits). It is also in
the live release's `.env` as `ORIGIN_VERIFY_SECRET=` and in the CloudFront origin config. The API
itself does not read it.

**It survives a redeploy and a rebuild.** `deploy-backend.yml`'s step "Require CloudFront's origin
header in nginx" rewrites the snippet from the secret on every deploy, and writes the `.env` line
into the new release's `.env`. It fails the deploy if the secret is unset, and puts the old config
back if `nginx -t` refuses the new one. `infra/terraform/user_data.sh` writes the include plus a
fail-closed snippet (`allow 127.0.0.1; deny all;`), so a freshly built box turns everyone away until
its first deploy. The file stays under EC2's 16 KB user-data cap: 16,303 bytes.

**Terraform matches the live estate.** `main.tf` declares `aws_security_group.cloudfront_origin`,
drops the open 80/443 blocks from `aws_security_group.api` and attaches both groups to
`aws_instance.api`. The new group was created with the CLI and then imported
(`terraform import aws_security_group.cloudfront_origin sg-0e379f2831c851008`, state serial bumped
in `s3://designrepo-media-626159998512/tfstate/designer-portal.tfstate`). A plan targeted at
`aws_security_group.api`, `aws_security_group.cloudfront_origin` and `aws_instance.api` reads
"No changes". The group's name and description are what it was created with; changing either
replaces it.

TLS: CloudFront talks to this origin over plain HTTP (`http-only`), so the box holds no certificate
and there is no Let's Encrypt renewal to keep alive.

## Ids

| Thing | Id |
|---|---|
| Instance | `i-06c0f0069a6ee942f` (`designrepo-api`, t3.medium, Elastic IP `13.206.216.18`) |
| Old group (kept, still attached) | `sg-0e6f0d71a66bc4062` (`designrepo-api`, also the deploy's port-22 window, `AWS_SG_ID`) |
| New group | `sg-0e379f2831c851008` (`cloudfront-origin-only-designer`) |
| Prefix list | `pl-9aa247f3` |
| Distribution | `E2XKM13U9R3Q6` (`d3ekigkotd1xa2.cloudfront.net`) |
| VPC | `vpc-0010b4077934ea06d` |

The stopped previous box `i-0e091ca8e6b417b52` shares the old group and therefore lost its open
80/443 too. If it is ever started again, attach the new group to it as well.

## Before and after

| | Before | After |
|---|---|---|
| `sg-0e6f0d71a66bc4062` inbound | 80, 443 from `0.0.0.0/0` | nothing standing; the deploy still opens and closes its `/32` on 22 (untouched) |
| `sg-0e379f2831c851008` inbound | did not exist | 80 from `pl-9aa247f3` |
| Origin custom headers | none | `X-Origin-Verify` |
| nginx | proxies everything | 403 without the header |

Verified on 2026-10-17 (status codes):

| Request | Before | After the nginx check | After the security group |
|---|---|---|---|
| `https://d3ekigkotd1xa2.cloudfront.net/health` | 200 | 200 | 200 |
| `POST …cloudfront.net/api/auth/login`, wrong password | 401 | 401 | 401 |
| `/health/ready` and a CORS preflight through CloudFront | 200 | 200 | 200 |
| `http://13.206.216.18/health`, no header | 200 | 403 | no answer (timeout) |
| the same with a guessed `X-Origin-Verify` | 200 | 403 | no answer (timeout) |
| `https://13.206.216.18/` | no answer | no answer | no answer |

Nothing else called the box directly: the web app, the Android app, `monitor.yml` and
`publish-android.yml` use the CloudFront URL, and the deploy's health checks run on the box against
`127.0.0.1:8000`, which skips nginx. The API was not restarted for any of this; nginx was reloaded.

**The deploy checks CloudFront, not just the box.** After writing the snippet and reloading nginx,
"Require CloudFront's origin header in nginx" asks `https://d3ekigkotd1xa2.cloudfront.net/health` (through CloudFront)
for up to two minutes. Loopback is exempt from the check, so only that answer proves the Actions
secret equals the CloudFront header. Anything but 200 (a mismatch shows as 403) puts the previous
nginx config back and fails the deploy before the new code is switched in.

## Changing the secret

Do it without a 403 window. Have the box accept both values while CloudFront switches over:

1. Generate 48 letters and digits. On the box (SSM), add a second line to
   `/etc/nginx/snippets/origin-verify.conf`:
   `if ($http_x_origin_verify = "<new>") { set $origin_verified 1; }`, then
   `nginx -t && systemctl reload nginx`.
2. Put `<new>` in the distribution's `X-Origin-Verify` origin header and wait for `Deployed`.
3. `gh secret set ORIGIN_VERIFY_SECRET` with `<new>`, then run "Deploy backend to EC2". It rewrites the
   snippet with the new value only and writes the new `.env` line.

## Rollback

In this order (open the box first, then stop sending the header):

```bash
# 1. Re-open port 80/443 on the old group and detach the new one
aws ec2 authorize-security-group-ingress --region ap-south-1 --group-id sg-0e6f0d71a66bc4062 \
  --ip-permissions 'IpProtocol=tcp,FromPort=80,ToPort=80,IpRanges=[{CidrIp=0.0.0.0/0,Description="HTTP (nginx)"}]' \
                   'IpProtocol=tcp,FromPort=443,ToPort=443,IpRanges=[{CidrIp=0.0.0.0/0,Description="HTTPS (nginx)"}]'
aws ec2 modify-instance-attribute --region ap-south-1 --instance-id i-06c0f0069a6ee942f --groups sg-0e6f0d71a66bc4062
aws ec2 delete-security-group --region ap-south-1 --group-id sg-0e379f2831c851008
terraform state rm aws_security_group.cloudfront_origin   # in infra/terraform, and revert main.tf

# 2. Drop the nginx check (as root on the box, via SSM). Reload, never restart.
sed -i '/snippets\/origin-verify.conf/d' /etc/nginx/sites-available/fieldrepo
nginx -t && systemctl reload nginx

# 3. Remove the X-Origin-Verify header: get-distribution-config, delete it from
#    Origins.Items[0].CustomHeaders, update-distribution --if-match <ETag>.
```

Before re-deploying after a rollback, remove the "Require CloudFront's origin header in nginx" step
from `deploy-backend.yml` too, or it puts the check straight back. Then set `FORWARDED_CLIENT_HOPS`
back to 1, because without the lock the 2nd-from-right entry is caller-controlled.

## How this document is kept true

Everything above is live AWS state as of 2026-10-17, and each claim has a one-line check:

| Claim | Kept true by |
|---|---|
| The groups and their rules | `aws ec2 describe-instances --region ap-south-1 --instance-ids i-06c0f0069a6ee942f --query "Reservations[].Instances[].SecurityGroups"` and `aws ec2 describe-security-groups --region ap-south-1 --group-ids sg-0e379f2831c851008` |
| The origin header is sent | `aws cloudfront get-distribution --id E2XKM13U9R3Q6 --query "Distribution.DistributionConfig.Origins.Items[0].CustomHeaders.Items[].HeaderName"` lists `X-Origin-Verify` (names only; never print the value) |
| The lock holds | `curl -s -o /dev/null -w '%{http_code}' https://d3ekigkotd1xa2.cloudfront.net/health` is 200, and `curl -m 10 http://13.206.216.18/health` gets no answer |
| The deploy keeps it | `grep -n "Require CloudFront's origin header" .github/workflows/deploy-backend.yml` and `grep -n origin-verify infra/terraform/user_data.sh` |

# Deploying the Design Prototype Workshop backend + media storage on AWS (free tier)

Architecture for the cheapest durable setup:

| Concern        | Service                | Persistence |
|----------------|------------------------|-------------|
| Database       | **Managed PostgreSQL** (not provisioned here) | Already persistent. This guide provisions no database and names no provider: the box only needs a `DATABASE_URL`. Which provider is deployed is recorded once, under "The database" in [../docs/ENVIRONMENT.md](../docs/ENVIRONMENT.md). |
| Object storage | **AWS S3**             | Durable, 11 9's |
| API server     | **AWS EC2 (t3.medium)** | The only piece you host |
| Web frontend   | **Vercel** (free) or the same EC2 | — |

Keep the database off the box and media on S3, so the EC2 box is stateless and can be rebuilt
anytime without data loss. That is the whole reason this guide provisions no database.

---

## 1. Which EC2 instance

- **Recommended: `t3.medium`** — 2 vCPU (burstable), **4 GiB RAM**, **not free-tier eligible**
  ($0.0448/hr on demand in `ap-south-1`, ~$33/month; AWS price list, read 2026-10-09). This is what
  `infra/terraform/main.tf` declares since 2026-10-09 for the Ubuntu 26.04 rebuild: Python 3.14 holds
  ~170 MiB more per process than 3.12, ~356 MB across uvicorn and the queue worker, and on 2 GiB that
  comes out of the memory the app sizes its media reads by ([docs/OPEN_FINDINGS.md](../docs/OPEN_FINDINGS.md)).
  Production has run on a **`t3.small`** (2 GiB, $0.0224/hr) since 2026-09-17, until that rebuild replaces it.
- **`t3.micro` (1 GiB, free-tier eligible) is no longer enough, and that is a measurement rather
  than caution.** It was the recommendation here until 2026-09-17, when a deploy failed with the
  API unable to answer `/health` on its own loopback for five minutes. The box was thrashing —
  1 GiB in swap, ~14 MB/s paging both ways, 85% iowait, and steal time 0, so not CPU credits.
  `systemctl status fieldrepo` showed uvicorn pinned to its 500M cgroup ceiling with 484 MB of it
  swapped out; released, the same process settled at **634 MB**. uvicorn alone wants ~634 MB, which
  does not fit in 911 MiB beside the queue worker, nginx and the kernel. The old cgroup ceilings
  were not sizing it, they were forcing it through swap.
- `t2.micro` is the older free-tier option. Both free-tier types are 1 GiB and neither fits this
  workload any more; choose one only for a cut-down deployment with the media queue worker
  disabled, and expect to tune the ceilings below downwards.
- **Do NOT** try to `npm run build` the Next.js frontend on this box either way — deploy the
  frontend to **Vercel**, which is what this project does (see `docs/DEPLOYMENT_VERCEL.md`).
- AMI: **Ubuntu Server 26.04 LTS** (resolute) for a new box — what `infra/terraform/main.tf` selects
  since 2026-10-09; the box running today is 24.04 (noble), and both are covered below. Storage:
  **30 GiB gp3** (free-tier max).
- Add a **2 GiB swap file** (below) so `pip install` / `prisma generate` don't get OOM-killed.

> The "Free tier eligible" badge on larger types (m7i-flex.large etc.) refers to the new account
> credits plan, not the classic 750-hour free tier. There is no genuinely free instance type that
> fits this workload — `t3.micro` is the only classic-free-tier option and the measurement above
> is why it was abandoned. Budget for the box.

---

## 2. Launch + network

1. **Launch instance** → Ubuntu 26.04, `t3.medium`, an **encrypted** gp3 root volume, new key pair
   (download the `.pem`).
2. **Elastic IP**: Allocate one and **associate it** with the instance. This gives a *stable* public
   IP (DHCP-style changes were exactly the LAN problem earlier — don't repeat it in the cloud).
3. **Security group (inbound rules):**
   - `22/tcp` SSH — **source: My IP** only.
   - `8000/tcp` API — source `0.0.0.0/0` for a quick demo (or restrict to your IP). If you put nginx
     in front, open `80`/`443` instead and keep 8000 internal.

---

## 3. Provision the box

```bash
ssh -i your-key.pem ubuntu@<ELASTIC_IP>

# swap (protects 1 GiB box during installs)
sudo fallocate -l 2G /swapfile && sudo chmod 600 /swapfile && sudo mkswap /swapfile && sudo swapon /swapfile
echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab

# libatomic1 is for the Node 26 the deploy pins for the Prisma CLI; curl and ca-certificates fetch the
# interpreter below. No Python package from apt: neither Ubuntu's own python3.14 nor a PPA.
sudo apt update
sudo apt install -y git libatomic1 curl ca-certificates openssl

# UPSTREAM CPYTHON 3.14.8 — what every release venv is built with, the same pinned python-build-standalone
# build the deploy installs (BOX_PYTHON_* in .github/workflows/deploy-backend.yml; take the four values
# from there, never from memory). These are that step's own commands: a private root-owned staging
# directory beside the target, the digest checked BEFORE anything is unpacked, the standard library
# byte-compiled as root (the units' user cannot write __pycache__ there), and one rename into a
# root-owned directory named for the version and the build.
PY_VERSION=3.14.8 PY_BUILD=20261009
PY_URL="https://github.com/astral-sh/python-build-standalone/releases/download/${PY_BUILD}/cpython-${PY_VERSION}%2B${PY_BUILD}-x86_64-unknown-linux-gnu-install_only.tar.gz"
PY_SHA256=83f9cb480b702548c592443f86209cf0fc03448d90692df257f095c01791dccc
PY_HOME="/opt/cpython/${PY_VERSION}+${PY_BUILD}"
sudo install -d -o root -g root -m 0755 /opt/cpython
STAGE="$(sudo mktemp -d /opt/cpython/.incoming.XXXXXX)"
sudo curl -fsSL -o "$STAGE/python.tar.gz" "$PY_URL"
echo "${PY_SHA256}  $STAGE/python.tar.gz" | sudo sha256sum -c -   # stop here if this says FAILED
sudo tar -xzf "$STAGE/python.tar.gz" -C "$STAGE" --no-same-owner && sudo rm -f "$STAGE/python.tar.gz"
sudo "$STAGE/python/bin/python3.14" -I -m compileall -q "$STAGE/python/lib/python3.14"
echo "$PY_SHA256" | sudo tee "$STAGE/python/.pbs-sha256" >/dev/null   # the deploy checks this marker
echo "$PY_URL" | sudo tee "$STAGE/python/.pbs-url" >/dev/null
sudo chown -R root:root "$STAGE/python" && sudo chmod -R go-w "$STAGE/python"
sudo mv -T "$STAGE/python" "$PY_HOME" && sudo rm -rf "$STAGE"
"$PY_HOME/bin/python3.14" -VV

# The release layout the deploy expects. See §3.1 — do not clone into /home/ubuntu/app/backend.
mkdir -p /home/ubuntu/app/releases/manual/backend
# Say "a human is using this" before the long copy below. Since 2026-09-17 the deploy's prune step
# sweeps any release directory that is more than an hour old and has never been flipped onto — and
# `cp -a` of a repo plus a venv, on a t3.micro, can easily outlast the hour. A push to main landing
# during the incident QUEUES (it does not overlap), so it runs the moment the box is free and would
# otherwise delete the tree you are half way through building. Remove the file when you are done, or
# leave it: the first automated deploy after `current` points here marks this release properly.
touch /home/ubuntu/app/releases/manual/.keep
git clone <YOUR_REPO_URL> /tmp/repo
cp -a /tmp/repo/backend/. /home/ubuntu/app/releases/manual/backend/
ln -sfn /home/ubuntu/app/releases/manual /home/ubuntu/app/current
cd /home/ubuntu/app/current/backend

"$PY_HOME/bin/python3.14" -m venv .venv
# FROM THE LOCK, and then the project with NO dependency resolution. `pip install -e .` on its own
# re-resolves this project's declared ranges and can lift a pin the lock had settled, which is the
# whole property the lock exists to have — CI, this box and a developer's venv resolving to the same
# versions. See docs/CI.md §1.3 for how the lock is refreshed (in a container, deliberately).
./.venv/bin/pip install -r requirements.lock
./.venv/bin/pip install -e . --no-deps
# Through the script, not a bare `prisma generate`: it adds the one line that keeps the generated
# client importable in seconds on Python 3.14 (without it, minutes and well over a gigabyte).
PATH="$PWD/.venv/bin:$PATH" ./.venv/bin/python scripts/generate_prisma_client.py
```

**Running the Prisma CLI by hand** (`migrate status`, `migrate deploy`) — use the Node the deploy
pinned, the same way the deploy does, or prisma-client-py falls back to whatever older nodeenv it
finds in `~/.cache/prisma-python/nodeenv`:

```bash
cd /home/ubuntu/app/current/backend
export PRISMA_NODEENV_CACHE_DIR="$HOME/.cache/prisma-python/nodeenv-26.11.1"   # = PRISMA_CLI_NODE_VERSION
export PRISMA_USE_GLOBAL_NODE=false PRISMA_USE_NODEJS_BIN=false
set -a; . ./.env; set +a
./.venv/bin/python -m prisma migrate status
```

Do NOT set `PRISMA_NODEENV_EXTRA_ARGS` to pin Node instead: prisma-client-py 0.15.0 passes it to
pydantic 2 as a raw string, it fails validation, and every `prisma` command dies before it starts.

Create `backend/.env` (see template in section 5) **inside the release**, at
`/home/ubuntu/app/current/backend/.env`.

### 3.1 The release layout, and why the paths below all go through `current`

Since 2026-09-03 the deploy does not write over a live tree. It rsyncs into a directory named for the
commit and flips a symlink:

```
/home/ubuntu/app/releases/<sha>-<run_id>.<attempt>/backend/   one directory per deploy ATTEMPT, its own .venv inside
/home/ubuntu/app/current -> releases/<sha>-<run_id>.<attempt>  the symlink both systemd units point through
```

Every path in the units below therefore names `current`, and every release carries **the `.env` it
was deployed with** — so the `.env` of the release that is currently serving is never touched by a
deploy that may yet fail. The last three RELEASES are kept — releases, not directories: since
2026-09-17 a directory is named per deploy ATTEMPT, and one left behind by a run that failed before
the symlink flip was never served, is not a rollback target, and is swept separately once it is an
hour old. The deploy marks a release `.released` at the instant it flips onto it, and the prune ranks
on that. `current` is never pruned whatever its age, because a deploy whose migration failed
deliberately leaves it pointing at an older release than the newest directories on disk.

**Since 2026-10-19 a release never stops the live API** (§3.2): it starts beside it on the other
loopback port and nginx switches when it is healthy. **Rollback** is the same move backwards, no
build:

```bash
aws ssm start-session --target <INSTANCE_ID>       # there is no standing SSH access to this box
bash /home/ubuntu/app/current/backend/deploy/blue_green.sh status              # live port, each port's release
bash /home/ubuntu/app/current/backend/deploy/blue_green.sh rollback            # back to the release behind the other port
ls -lt /home/ubuntu/app/releases                   # or name an older one:
bash /home/ubuntu/app/current/backend/deploy/blue_green.sh rollback <older-release>
```

**Never past the Aadhaar fingerprint release once it has taken an artisan write.** That migration
is additive, so nothing here stops the flip, but older code would show those artisans with no
Aadhaar, register the same person twice and start storing full numbers again. Check the query in
"Aadhaar fingerprint: the run order" below first, and fix forward if it is not 0.

**A release runs the interpreter it was built with, and a rollback needs that interpreter still
installed.** Releases built before 2026-10-09 run Python 3.12 — their venvs symlink
`/usr/bin/python3.12`, Ubuntu 24.04's own interpreter. A release built from the deadsnakes PPA's
`python3.14` — which this runbook and the deploy named on 24.04 for a few hours of 2026-10-09, before
any deploy ran it — would run `/usr/bin/python3.14`. Every release since runs the pinned upstream
CPython under `/opt/cpython/<version>+<build>`. So do not remove `python3.12`, a deadsnakes
`python3.14`, or an older `/opt/cpython/*` build while a release in `releases/` still runs it —
`readlink -f releases/*/backend/.venv/bin/python` says which, and the deploy's install step prints the
same list and warns about any that will not run. The deploy keeps three released trees, so old ones
age out on their own three deploys later. A 24.04 box that has the deadsnakes PPA keeps it until then;
the deploy neither adds it nor upgrades from it any more, and the step's comment has the one-line
removal for afterwards.

Seconds, no network fetch. **The one thing a flip cannot undo is an applied migration** — if the
release you are rolling back to predates a migration that has run, the old code meets a schema it was
not written for. That is a restore, not a rollback. It is also why a failed `prisma migrate deploy`
does **not** flip: the box stays on code that matches the schema the database actually has.

`.github/workflows/deploy-backend.yml` additionally installs systemd **drop-ins**
(`/etc/systemd/system/fieldrepo-queue.service.d/`, and `fieldrepo.service.d/` only while the old
single-port API unit still exists) rather than replacing the units below, so a
hand edit on a long-lived box is not silently discarded. Undo them with
`rm -rf /etc/systemd/system/fieldrepo*.service.d && systemctl daemon-reload`. The drop-ins and the
units in this section are deliberately redundant and are not allowed to disagree; `docs/CI.md` §1.2
is the same story from the pipeline's side.

### 3.2 Zero-downtime releases (2026-10-19)

Until this date a deploy flipped `current` and restarted the API in place (stopping it first when a
migration was pending), so every deploy spent the API's start-up answering 502. Now:

| Path | What it is |
|---|---|
| `/home/ubuntu/app/slot-8000`, `slot-8001` | A link per loopback port to the release that port runs. |
| `fieldrepo-api@8000`, `fieldrepo-api@8001` | The API on that port. One runs; it is the one enabled for boot. |
| `/etc/nginx/sites-available/fieldrepo` | `proxy_pass http://127.0.0.1:<port>;` names the live port. nginx is the truth. |
| `/home/ubuntu/app/current` | The live release, as before: the queue, the one-off scripts and this guide go through it. |

`backend/deploy/blue_green.sh release <id>`, piped over ssh from the commit being deployed, points
the idle port's slot at the new release, pauses the queue, runs `prisma migrate deploy` while the
live port serves, starts the idle port, waits up to **ten minutes** for its `/health` (a line every
twenty seconds), reloads nginx onto it and checks `/health` through nginx, and only then stops the
old port, flips `current` (marking the release `.released`) and restarts the queue. Anything that
fails before the switch stops the new port and leaves nginx on the old one, which never stopped. The
prune never deletes a release a slot points at, and a directory it cannot delete (root-owned, say)
is retried with sudo and otherwise left with a warning, never a red deploy.
`backend/deploy/test_blue_green.sh` (Checks, "Deploy scripts") proves each path against stubs.

**Migrations run while the old release serves,** so a release's migrations must keep the previous
release working: add first, drop in a later release. If `prisma migrate deploy` fails three times
with the queue stopped, **for any reason**, the script stops the live API and tries five more times,
the one path that still has downtime, with a warning in the log. The fallback exists for a pooler
with no session left for the schema engine (EMAXCONNSESSION), but the script does not read the
error, so a migration that is simply wrong also takes the API down for the five further attempts
(about a minute) before the old port is restarted and the run fails. Read the three attempts in the
log to tell the two apart.

```bash
bash /home/ubuntu/app/current/backend/deploy/blue_green.sh status      # live port, each port's release
bash /home/ubuntu/app/current/backend/deploy/blue_green.sh restart     # after a hand edit of the live .env: restarts in place, so it does stop the API
bash /home/ubuntu/app/current/backend/deploy/blue_green.sh rollback    # start the other port's release and switch back to it
sudo journalctl -u fieldrepo-api@8001 -n 80 --no-pager                 # the port status names
```

Smoke test, then run as a service:

```bash
./.venv/bin/python -m uvicorn app.main:app --host 0.0.0.0 --port 8000   # Ctrl-C after /health works
```

### systemd unit (keeps it running + restarts on reboot)

> **FIRST, WHICH BOX.** This portal and the field repository run SEPARATE EC2 instances, separate S3
> buckets (`designrepo-media-626159998512` here, `fieldrepo-media-626159998512` there) and separate
> Vercel projects — but IDENTICAL systemd unit names, because `infra/terraform/user_data.sh` writes
> `/etc/systemd/system/fieldrepo.service` as a literal string instead of templating it from
> `var.project`. Nothing in a unit name, an nginx site or `deploy-backend.yml` tells the two apart.
> **The only thing that decides which machine a push touches is the `EC2_HOST` secret in the
> repository being pushed, so those secrets must never be copied between the two repositories.**
> `docs/ENVIRONMENT.md` rule 4 carries the full table and the failure this prevents.

> **On the `fieldrepo` unit names below.** They are the product's pre-rebrand name and they are
> deliberate, not leftovers. These strings are not labels in a config file — they are the names of
> units that are installed and enabled on the live instance, and `.github/workflows/deploy-backend.yml`
> stops them, restarts them and reads their journals **by that literal name** on every push to `main`.
> Renaming them here renames nothing on the running box; it only makes this runbook disagree with the
> machine, so the next operator who follows it runs `systemctl status design-workshop` against a unit
> that does not exist, concludes the API is down, and starts debugging an outage that is not
> happening. Worse is the version where somebody also "corrects" the workflow to match: the deploy
> then unpacks new code and fails at the restart step, leaving the old process serving old code while
> the pipeline reports what looks like an application error. `infra/terraform/user_data.sh` pins the
> same names for the same reason. Renaming is a migration performed in one change — `systemctl
> disable --now` the old units over SSH, install the new ones, and edit the workflow — not an edit.

`/etc/systemd/system/fieldrepo-api@.service`, since 2026-10-19 a template with one instance per
loopback port (8000, 8001), each running the release its `/home/ubuntu/app/slot-<port>` link names
(§3.2). `backend/deploy/blue_green.sh` installs it on every deploy and the first one removes the old
single-port `fieldrepo.service` and its drop-ins; `infra/terraform/user_data.sh` writes the same file:

```ini
[Unit]
Description=Design Prototype Workshop API on port %i
After=network.target

[Service]
User=ubuntu
WorkingDirectory=/home/ubuntu/app/slot-%i/backend
EnvironmentFile=/home/ubuntu/app/slot-%i/backend/.env
# ONE worker, bound to localhost (nginx fronts it). Two workers reintroduced the
# SIGKILL/orphaned-Prisma-engine 500 outage (commit 44923bc); the media queue runs in its own
# service below, so the web process must have MEDIA_QUEUE_WORKER_ENABLED=false in .env.
Environment=MEDIA_QUEUE_WORKER_ENABLED=false
ExecStart=/home/ubuntu/app/slot-%i/backend/.venv/bin/python -m uvicorn app.main:app --host 127.0.0.1 --port %i --workers 1
# A SOFT ceiling only, and no MemoryMax: over it the cgroup is throttled and reclaimed hard, so a
# heavy request gets slow instead of everything else getting killed. Killing uvicorn to save memory
# is the outcome these limits exist to prevent — on a 1 GiB box the kernel's OOM killer picks by
# badness score, and that has meant uvicorn dying because the QUEUE was hungry, with the API then
# 502ing for a reason that appears nowhere in its own log.
# 1000M since 2026-09-17, raised from 500M. uvicorn was measured at 634 MB once the old ceiling was
# lifted; 500M had been holding it there with 484 MB in swap. ~1.6x the measured figure, so ordinary
# variation never reaches the ceiling. See section 1.
MemoryAccounting=yes
MemoryHigh=1000M
Restart=always
RestartSec=10
KillMode=control-group
TimeoutStopSec=20

[Install]
WantedBy=multi-user.target
```

`/etc/systemd/system/fieldrepo-queue.service` (the media/transcription queue, decoupled from the
web process so ffmpeg + AI work never blocks requests):

```ini
[Unit]
Description=Design Prototype Workshop media queue worker
After=network.target

[Service]
User=ubuntu
WorkingDirectory=/home/ubuntu/app/current/backend
EnvironmentFile=/home/ubuntu/app/current/backend/.env
ExecStart=/home/ubuntu/app/current/backend/.venv/bin/python -m app.worker
# The queue runs ffmpeg and AI transcription, and one large audio job can take it past what is left
# after uvicorn. MemoryHigh throttles it first (slow, still working); MemoryMax kills it if it keeps
# climbing, which is survivable — Restart=always brings it back and the job is retried. The queue is
# the process that CAN be killed; uvicorn is not, which is why only this unit has a hard stop.
# 800M/1100M since 2026-09-17 — the old 400M/550M doubled with the box, and UNLIKE the API's figure
# this one is still inherited arithmetic rather than a reading: 400M was never observed to be hit.
# If transcription work thrashes, raise it from the memory line in `systemctl status
# fieldrepo-queue`, not by doubling again.
MemoryAccounting=yes
MemoryHigh=800M
MemoryMax=1100M
KillMode=control-group
Restart=always
RestartSec=10

[Install]
WantedBy=multi-user.target
```

**The queue unit takes a host-wide advisory lock at startup and exits 1 if another drain already
holds it** (`services/media_queue.acquire_queue_worker_lock`, 2026-09-03). So a misconfiguration that
would once have run two drains — two ffmpeg workloads and two transcription providers billed against
a box sized for one — now shows as a **restart loop in `systemctl status fieldrepo-queue` naming the
cause**, rather than as two drains quietly billing the provider twice. `MEDIA_QUEUE_WORKER_ENABLED`
now also defaults to `false`, so the `Environment=` line above and the `.env` row in §5 are belt and
braces rather than the only guard; both stay exactly as they are, because a drop-in that re-points
`EnvironmentFile=` moves it *after* the base unit's `Environment=` and a `.env` that ever gained the
variable would otherwise win.

By hand, point a slot at the release first (`ln -s releases/manual /home/ubuntu/app/slot-8000`):

```bash
sudo systemctl daemon-reload && sudo systemctl enable --now fieldrepo-api@8000 fieldrepo-queue
sudo systemctl status fieldrepo-api@8000 fieldrepo-queue
curl http://localhost:8000/health
```

---

## 4. S3 bucket

1. **Create bucket** (globally-unique name), in the **same region** as `AWS_REGION`.
2. **Public read for media** (simplest so the app can show images/audio/video by URL):
   - Bucket → Permissions → **uncheck "Block all public access"**.
   - Add this bucket policy (read-only GET on the `media/` prefix; uploads stay private via presign):
     ```json
     {
       "Version": "2012-10-17",
       "Statement": [{
         "Sid": "PublicReadMedia",
         "Effect": "Allow",
         "Principal": "*",
         "Action": "s3:GetObject",
         "Resource": "arn:aws:s3:::YOUR_BUCKET/media/*"
       }]
     }
     ```
   - (More locked-down alternative: keep private and serve via presigned GET URLs — needs a small code
     addition; ask if you want it.)
3. **CORS** (needed for the **web** browser's presigned PUT/GET; the Android app uses OkHttp and is
   unaffected). Bucket → Permissions → CORS:
   ```json
   [{
     "AllowedHeaders": ["*"],
     "AllowedMethods": ["PUT", "GET", "HEAD"],
     "AllowedOrigins": ["https://your-frontend-domain"],
     "ExposeHeaders": ["ETag"]
   }]
   ```
4. **IAM user** (programmatic access) with this policy, then create an access key:
   ```json
   {
     "Version": "2012-10-17",
     "Statement": [{
       "Effect": "Allow",
       "Action": ["s3:PutObject", "s3:GetObject", "s3:DeleteObject"],
       "Resource": "arn:aws:s3:::YOUR_BUCKET/*"
     }]
   }
   ```
   (`DeleteObject` is required for the cancel-staged-upload cleanup.)

---

## 5. backend/.env template (production)

Fully annotated version: `backend/.env.example`. Every variable with its default, whether it is
required and whether it is a secret: [docs/ENVIRONMENT.md](../docs/ENVIRONMENT.md).

```dotenv
# The connection string, and it is read by two things with different needs. `prisma migrate deploy`
# reads it RAW (out of schema.prisma's env("DATABASE_URL")) and needs SESSION mode — advisory locks
# and DDL, which transaction pooling cannot hold across statements. The running app reads it through
# Settings and wants the POOLED endpoint where the provider publishes one.
#
# DATABASE_USE_TRANSACTION_POOLER (default TRUE) is how you say which of the two this value is. It
# matches no hostname: true adds `pgbouncer=true` to any remote DSN. SET IT FALSE if the host below
# is a direct, non-pooling endpoint — on this single-box deploy, where one .env serves both uvicorn
# and the migration step, that is usually the case.
DATABASE_URL=postgresql://USER:PASSWORD@YOUR-POSTGRES-HOST:5432/DBNAME
# DATABASE_USE_TRANSACTION_POOLER=false   # only if the host above is NOT a transaction pooler
# DATABASE_CONNECTION_LIMIT=10   # per worker; do NOT raise to 40 — that exhausted the pooler
JWT_SECRET=<long-random>
MASTER_ADMIN_EMAIL=you@example.com
# DEFAULT_SIGNUP_ROLE=CROWDSOURCE_VOLUNTEER  # tier for brand-new Google sign-ins

# Real S3 — leave AWS_S3_ENDPOINT UNSET so boto3 talks to AWS (it was localhost:9000 for MinIO).
# Use the DUAL-STACK public base URL so media loads on IPv6-only mobile networks.
AWS_ACCESS_KEY_ID=AKIA...
AWS_SECRET_ACCESS_KEY=...
AWS_REGION=ap-south-1
AWS_S3_BUCKET=your-bucket
AWS_S3_PUBLIC_BASE_URL=https://your-bucket.s3.dualstack.ap-south-1.amazonaws.com

# Speech-to-text provider chain (any subset; priority: ElevenLabs -> Deepgram -> Whisper).
# OPENAI_API_KEY also powers transcript refinement/translation.
ELEVENLABS_API_KEY=...
DEEPGRAM_API_KEY=...
OPENAI_API_KEY=...
GEMINI_API_KEYS=...,...

# The web service must NOT drain the media queue (fieldrepo-queue does).
MEDIA_QUEUE_WORKER_ENABLED=false

# E-mail through Amazon SES (optional). Unset MAIL_FROM_ADDRESS = no e-mail and no e-mail controls.
# The address must be SES-verified (address or domain) in MAIL_SES_REGION, the account must have SES
# production access there, and the AWS_ACCESS_KEY_ID user above needs ses:SendEmail on it. The
# fieldrepo-queue unit sends; uvicorn only queues. docs/ENVIRONMENT.md "E-mail (Amazon SES)".
# MAIL_FROM_ADDRESS=no-reply@your-domain
# MAIL_FROM_NAME=Design Prototype Workshop
# MAIL_REPLY_TO=support@your-domain
# MAIL_SES_REGION=ap-south-1
# MAIL_SES_CONFIGURATION_SET=

# Ministry export packs (optional, OFF by default). Apply infra/terraform FIRST — it adds the
# AppMinistryExports statement (Put/Get on exports/*) and the expire-ministry-exports lifecycle rule —
# and only then set the flag. With the flag on and no apply, every pack build fails at the upload.
# MINISTRY_EXPORTS_ENABLED=true

BACKEND_CORS_ORIGINS=https://your-frontend-domain
# Required in production: the origin every set-password and invite link is built on. The default,
# http://localhost:3000, only suits a laptop. Use the same origin as the first CORS entry above.
NEXT_PUBLIC_APP_URL=https://your-frontend-domain
```

On this deployment those two values do not come from the template: `.github/workflows/deploy-backend.yml`
pins both, to `https://designer-repository.vercel.app`, on every deploy (§8.2).

Do **not** commit `.env`. Presigned PUTs use SigV4 (already configured in `services/s3.py`), so any
region works.

---

## 6. Point the apps at it

- **Android**: set `android/local.properties` → `apiBaseUrl=https://<YOUR_HTTPS_DOMAIN>/api/`, then
  `./gradlew.bat :app:assembleDebug` and reinstall. **Plain `http://` to a production host no longer
  works**: the manifest sets `android:usesCleartextTraffic="false"` and
  `res/xml/network_security_config.xml` permits cleartext only for `10.0.2.2`, `127.0.0.1` and
  `localhost`, so an `http://<ELASTIC_IP>:8000/api/` call fails with a
  `CLEARTEXT communication not permitted` error. Front the API with TLS (CloudFront, or nginx +
  certbot per §7) and use `https`. Developing against a LAN backend from a real phone: add your
  machine's private IP as an extra `<domain>` in the network security config **temporarily**, and do
  not commit it. Rationale in [docs/SECURITY.md](../docs/SECURITY.md) §1.4.
- **Web**: set `NEXT_PUBLIC_API_URL` to the API **origin only** — `https://d3ekigkotd1xa2.cloudfront.net`,
  with no `/api` suffix and no trailing slash, because `frontend/lib/api.ts` appends `/api` itself.
  Then add the frontend origin to `BACKEND_CORS_ORIGINS` and to the bucket CORS. Full runbook:
  [docs/DEPLOYMENT_VERCEL.md](../docs/DEPLOYMENT_VERCEL.md).

---

## 7. (Optional) HTTPS

Put nginx in front of uvicorn and run certbot:

```bash
sudo apt install -y nginx certbot python3-certbot-nginx
# proxy_pass http://127.0.0.1:8000; in a server block for your domain, then:
sudo certbot --nginx -d api.yourdomain.com
```

Then the API is `https://api.yourdomain.com/api/`, which is what the Android app already requires —
no change to the network security config is needed.

---

## 8. Automated deploy (Terraform + GitHub Actions + Vercel + nginx)

Everything below is codified in the repo so the only manual inputs are credentials.

### 8.1 Provision with Terraform (`infra/terraform/`)

Creates the **S3 bucket** (public-read `media/*` + CORS), an **IAM user** with
`PutObject/GetObject/DeleteObject` and a fresh **access key**, and a **t3.medium**
(a `t3.micro` until 2026-09-17 and a `t3.small` until the 2026-10-09 rebuild; §1 says why) EC2 box
on Ubuntu 26.04 with an encrypted root volume, an **Elastic IP**, a 2 GiB swap file, the pinned
upstream CPython the release venvs are built with, **nginx** (reverse proxy on 80,
so port 8000 is never exposed) and **ffmpeg** (needed for Whisper long-audio
chunking), plus the `fieldrepo` systemd unit. It creates **no database** — that stays off the box.

> Terraform/AWS auth needs an **IAM access key pair**, not the console
> email+password. Create an IAM admin user in the console first, then:
> `export AWS_ACCESS_KEY_ID=… AWS_SECRET_ACCESS_KEY=…` (do **not** use root keys).

```bash
cd infra/terraform
terraform init
terraform apply \
  -var="aws_region=ap-south-1" \
  -var="bucket_name=YOUR-GLOBALLY-UNIQUE-BUCKET" \
  -var="ssh_key_name=your-ec2-keypair" \
  -var="ssh_ingress_cidr=YOUR.IP/32" \
  -var='cors_allowed_origins=["https://your-app.vercel.app"]'

terraform output api_public_ip            # -> EC2_HOST
terraform output s3_bucket                # -> AWS_S3_BUCKET
terraform output s3_public_base_url       # -> AWS_S3_PUBLIC_BASE_URL
terraform output media_access_key_id      # -> AWS_ACCESS_KEY_ID
terraform output -raw media_secret_access_key   # -> AWS_SECRET_ACCESS_KEY (sensitive)
```

`terraform.tfstate` and `*.tfvars` are gitignored — they hold the generated
secret key; never commit them.

**Rebuilding THIS deployment's box is not an `apply`.** Since 2026-10-09 `aws_instance.api` declares
the Ubuntu 26.04 `t3.medium` with an encrypted root, and an encrypted root cannot be added to the
running instance, so every plan proposes replacing it — terminating production and moving the Elastic
IP onto an empty box. `prevent_destroy` makes that plan an error. The blue/green procedure that
replaces it (forget the old instance in the state, create the new one with `-target`, wait for its
first boot to finish — `cloud-init status --wait` over SSM — deploy to it, move the Elastic IP, stop
the old one) is written out on the `lifecycle` block of `aws_instance.api` in
`infra/terraform/main.tf`.

### 8.2 GitHub Actions secrets (auto-deploy on push)

`.github/workflows/deploy-backend.yml` waits for the same commit's `Checks` run, rsyncs `backend/`
into `releases/<sha>-<run_id>.<attempt>`, writes that release's `.env`, builds or reuses a venv from
`requirements.lock`, runs `prisma migrate deploy`, **flips the `current` symlink** and restarts the
units — on every push to `main` that touches `backend/`. Set these repo secrets
(**Settings → Secrets and variables → Actions**):

| Secret | Value |
|--------|-------|
| `EC2_HOST` | the Elastic IP (`terraform output api_public_ip`) |
| `EC2_SSH_KEY` | the **private** `.pem` contents for the EC2 key pair |
| `BACKEND_ENV` | the entire `backend/.env` file (template in §5, with the Terraform S3 values) |
| `DATABASE_URL` | the **session-pooler** DSN, read only by `.github/workflows/backup-db.yml` (2026-09-03). Not the transaction pooler: `pg_dump` holds one connection across the whole dump and sets session state. |
| `AWS_BACKUP_ACCESS_KEY_ID` / `AWS_BACKUP_SECRET_ACCESS_KEY` | the `designrepo-backup-writer` IAM user's key pair (2026-10-17, `infra/terraform/backups.tf`), so the backup job can add a dump under `backups/designer-portal/` and list it, and nothing else. SSM keeps a copy at `/iit/designer-portal/backup-writer-*`. Until 2026-10-17 the job used the API's own key as `AWS_MEDIA_*`; they now hold a copy of the backup-writer key, so never copy them into the box `.env` as the media key, and delete both once this change is on main. **Deliberately not `AWS_ACCESS_KEY_ID`** — that pair belongs to `designrepo-ci-sg`, whose policy allows exactly two security-group calls and is handed to the step that opens port 22. It must never be widened. |

The `.env` is piped to the server over the SSH tunnel — it is never written to
the workflow logs or a command line. Two of its keys are then overwritten in the workflow itself,
because they are public and must not drift: `BACKEND_CORS_ORIGINS` and, since 2026-10-09,
`NEXT_PUBLIC_APP_URL`, both pinned to the production web origin by the step *Pin the CORS origins
and the link origin*. **There is no standing SSH access to this box**: the deploy
opens port 22 for one runner IP and revokes it in an `if: always()` step, and a human reaches the box
with `aws ssm start-session`. `docs/CI.md` §1.2 is the full runbook, including the rollback.

### 8.3 Vercel (frontend)

> Full step-by-step runbook — import, env vars, preview vs production, custom domain, redeploy,
> troubleshooting — is **[docs/DEPLOYMENT_VERCEL.md](../docs/DEPLOYMENT_VERCEL.md)**. The summary
> below is what matters from the backend's point of view.

~~The Vercel project is linked to this GitHub repo (same account), so each push to
`main` auto-deploys.~~ The Vercel project has been **unlinked** from GitHub since 2026-07-27, and
`.github/workflows/deploy-frontend.yml` publishes it after this backend deploys, so the site never
calls an endpoint the box does not have yet ([docs/CI.md](../docs/CI.md) §1). In the Vercel project
settings:

- **Root Directory:** `frontend` (this is a monorepo; `frontend/vercel.json` pins the Next.js
  framework, `npm ci` install and `next build`). Leaving it at the repo root fails the build with
  "No Next.js version detected".
- **Environment variables:** `NEXT_PUBLIC_API_URL = https://d3ekigkotd1xa2.cloudfront.net` — the
  **origin only, without** `/api` and without a trailing slash (the web client appends `/api`
  itself; `…/api` produces `…/api/api/…` and every screen 404s). Plus the optional
  `NEXT_PUBLIC_GOOGLE_CLIENT_ID` and `NEXT_PUBLIC_MAPTILER_API_KEY`. Do **not** add
  `NEXT_PUBLIC_APP_URL` here expecting an effect — no code under `frontend/` reads it, so setting it
  in Vercel changes nothing. What lets the Vercel origin reach the API is its presence in
  `BACKEND_CORS_ORIGINS` (last bullet below), not its name in this dashboard. **The backend's
  variable of the same name is the opposite case: required in production**, because every
  set-password and invite link is built on it. The deploy pins it beside the CORS origins (§8.2),
  and [docs/ENVIRONMENT.md](../docs/ENVIRONMENT.md) has the row.
- **Redeploy after any env change.** `NEXT_PUBLIC_*` values are inlined into the bundle at build
  time, so editing them in the dashboard changes nothing until a fresh build runs (redeploy with
  the build cache disabled).
- **Mixed content:** an HTTPS Vercel page cannot call an HTTP API — browsers block it. The API is now
  fronted by **CloudFront over HTTPS** (`https://d3ekigkotd1xa2.cloudfront.net`, dual-stack), so use
  that `https://…` value above and the block is gone. (Hitting the raw EC2 origin over `http://…`
  would still be blocked.) The Android app talks to the same CloudFront URL.
- **Google sign-in:** add the Vercel origin to the Google OAuth web client's *Authorized JavaScript
  origins*, or the GSI button returns 403.
- Add the resulting Vercel URL to `BACKEND_CORS_ORIGINS` and to the bucket's
  `cors_allowed_origins` Terraform var. On this deployment the first lives in the pin step of
  `.github/workflows/deploy-backend.yml`, not in `BACKEND_ENV`, which that step overrides; change the
  backend's `NEXT_PUBLIC_APP_URL` there in the same edit. Both lists take **exact** origins — scheme
  + host, no trailing slash, no wildcards — so preview deployments (per-deployment hostnames) are not
  covered by the production entry.

### 8.4 Point the Android app at the box

`android/local.properties` → `apiBaseUrl=http://<EC2_HOST>/api/` (port 80 via
nginx — no `:8000`), then `./gradlew.bat :app:assembleDebug` and reinstall.

### 8.5 ffmpeg note

The long-audio Whisper chunking (`pydub`) needs the **ffmpeg** binary. Terraform's
`user_data.sh` installs it (`apt-get install -y ffmpeg`). If you provision a box
by hand, run `sudo apt install -y ffmpeg`, otherwise long recordings fall back to
a single-shot transcription attempt.

## Aadhaar fingerprint: the run order (2026-10-17)

The artisan register stops holding Aadhaar numbers. It keeps `Artisan.aadhaarFingerprint`
(HMAC-SHA256 of the 12 digits under `AADHAAR_FINGERPRINT_KEY`, unique) and `Artisan.aadhaarLast4`
(`backend/app/services/aadhaar_fingerprint.py`). The key is ONE value shared with the Field Repository
(backup: SSM SecureString `/iit/shared/aadhaar-fingerprint-key`; GitHub Actions secret
`AADHAAR_FINGERPRINT_KEY`, pinned into the release `.env` by deploy-backend.yml). Losing it makes
every fingerprint unmatchable, and it cannot be rotated without the numbers. Three steps, on each
box, in this order:

1. **Release with migration `20261022090000_aadhaar_fingerprint`.** Adds the two nullable columns,
   the unique index and shape checks. From this release on nothing writes a full number: create,
   edit, the review edit, the spreadsheet import and the duplicate lookup all fingerprint the typed
   number and drop it; every response and export shows `XXXX XXXX 9012`. Rows recorded earlier still
   hold the number in the legacy `aadhaarNumber` column and are still matched as duplicates.
2. **Backfill, on the box, after that release is live** (by SSM, as `ubuntu`, from the live release
   so it reads the same `.env`):

   ```
   cd /home/ubuntu/app/current/backend
   .venv/bin/python -m scripts.backfill_aadhaar_fingerprint             # dry run: counts only
   .venv/bin/python -m scripts.backfill_aadhaar_fingerprint --apply     # writes, then verifies
   ```

   It works in batches, is idempotent (run it again: "Nothing left"), prints counts only, verifies
   each fingerprint by reading it back, and rewrites any edit-history entry that still holds an old
   value into a valueless one. A legacy value that is not a valid Aadhaar is reported and left;
   `--apply --retract-unusable` clears those. Exit 0 means nothing is left.
3. **A LATER release ships the drop.** (Done 2026-10-19: on the 0.0.19 box the backfill found no
   legacy number, dry run and `--apply` both printed "Nothing left", and the next main commit
   shipped the migration.) Move `backend/prisma/deferred_migrations/
   20261029090000_drop_aadhaar_number/` into `backend/prisma/migrations/`, delete `aadhaarNumber`
   from `Artisan` in `schema.prisma`, and set `LEGACY_COLUMN_PRESENT = False` in
   `aadhaar_fingerprint.py`, in one commit. The migration refuses (and the deploy fails before the
   switch-over) while any row holds a number without its fingerprint, or any history entry still
   holds a value; if it refuses, run step 2, then
   `npx prisma migrate resolve --rolled-back 20261029090000_drop_aadhaar_number` and deploy again.
   Afterwards run `VACUUM FULL "Artisan"; VACUUM FULL "RecordRevision";` in the SQL editor so the old
   row versions holding numbers are gone from the data files. Copies taken before the drop still hold
   the numbers for a while, and for much longer than the nightly dumps' own 30 days (the lifecycle
   rules as applied on 2026-10-18; `infra/terraform/backups.tf` and `main.tf` hold them):
   - the nightly dumps in `designrepo-media-626159998512/backups/designer-portal/`: 30 days as the
     current version, then 90 more as noncurrent versions (the bucket is versioned), so about 120
     days;
   - their replica in `designrepo-offsite-626159998512` (ap-south-2): 35 days under Object Lock
     (GOVERNANCE), then 90 more as noncurrent versions, so about 125 days;
   - the weekly copy (`backups/designer-portal/weekly/`, the first dump of each ISO week), in both
     buckets: 365 days, then 90 more as a noncurrent version, so about 455 days;
   - the database provider's own backups and point-in-time recovery (WAL), for as long as its plan
     keeps them ([docs/ENVIRONMENT.md](../docs/ENVIRONMENT.md), "The database", names the provider).

   So a number is gone from every copy about 455 days after the drop (the last weekly copy taken
   before it), plus the provider's window; from the nightly copies alone, about 125 days. To end it
   sooner (an erasure request), an admin deletes the older nightly and weekly dump versions in both
   buckets (`--bypass-governance-retention` in ap-south-2).

**Rollback: do not roll the backend back past step 1 once it has taken an artisan write. Fix
forward.** The migration is additive, but the data written after it is not something older code can
read. From step 1 on, every artisan created or edited with an Aadhaar holds only the fingerprint and
the last four, with `aadhaarNumber` NULL. A release older than this one, put back by the symlink flip
or any other way, would:

- show those artisans as having no Aadhaar;
- let the same person be registered again, because its duplicate check reads `aadhaarNumber` only;
- start writing full numbers again on every create and edit.

None of that is visible as an error, so an easy rollback is the dangerous one here. If this release
misbehaves, ship a hotfix release on top of it. A rollback is safe only while this query, in the
SQL editor, still answers 0 (rows only this release writes; the backfill leaves `aadhaarNumber` set):

```sql
SELECT count(*) FROM "Artisan" WHERE "aadhaarFingerprint" IS NOT NULL AND "aadhaarNumber" IS NULL;
```

Step 2 changes nothing older code reads, so it does not change this. Step 3 is not reversible: the
numbers are gone, which is its purpose.

## 9. After a release: one-off scripts

Some releases change a rule that existing rows predate. Their script runs ONCE, on the box, after the
deploy has finished and `/health` answers, against the live database the box already points at.
Never from a laptop, and never as part of a test run. Each one is dry by default and safe to re-run.

### 9.1 Standard values: phone, gender, language, tool material (convergence point 10)

The release that adds `phoneCountryCode` and `materialFamily` checks new values against the shared
lists (`backend/app/data/standard_values.json`, the same file in both portals). Rows saved before it
keep their old spellings until this runs: phones (artisans and designer profiles) become digits plus a country code (`+91` when none
is given), genders take their list label, interview languages their standard code (`Bangla` and
`Bengali` become `bn`), and a tool's material family is set where its free `material` text names
exactly one family. A tool's tradition held under a retired code takes the owner's wording (`MODERN`
becomes `CONTEMPORARY`, "Contemporary"; `HYBRID` becomes `TRANSITIONAL`, "Transitional (Hybrid)"),
and the report lists each old value with what it becomes. Crafts with no shared code are listed with candidate codes for the owner; the
script never writes a craft code. Writes leave `updatedAt` alone, so no offline edit conflicts.

**The owner reads the dry-run report and approves it before `--apply`.**

```bash
cd /home/ubuntu/app/current/backend
.venv/bin/python scripts/standardise_values.py --report-dir /tmp   # dry run: counts, every unmappable value, craft proposal
.venv/bin/python scripts/standardise_values.py --apply              # only after the owner approves the report
.venv/bin/python scripts/standardise_values.py                      # again: expect 0 to change
```

The report file is named `DesignWorkshop_Database_<date>_<time>_Standard-Values_Report.json`. A value no
list entry matches is left as it is and listed with its row ids; phone numbers print with their last
four digits only (`--show-values` prints them in full; the JSON report holds them in full).

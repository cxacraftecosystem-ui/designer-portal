#!/usr/bin/env bash
# First-boot provisioning for the Design Prototype Workshop API box (Ubuntu 24.04).
# Installs system deps (including ffmpeg for Whisper audio chunking and nginx as
# the reverse proxy so port 8000 is never exposed directly), prepares a swap file
# so installs don't OOM on 1 GiB, and lays down the nginx site + systemd unit.
# The actual code is deployed by the GitHub Actions workflow (deploy-backend.yml).
#
# ON THE `fieldrepo` NAMES BELOW. The unit files, the nginx site and the systemctl calls kept their
# pre-rebrand names on purpose. These are not strings in a config file — they are the names of units
# that are already installed and running on the live instance, and `.github/workflows/deploy-backend.yml`
# stops, restarts and reads journals for them BY NAME on every push to main. This script only ever
# runs once, at first boot of a NEW box, so renaming here would rename nothing on the existing one:
# the deploy would `systemctl restart design-workshop` against a unit that does not exist, exit
# non-zero, and leave the old process running the old code while the pipeline reports a failure that
# looks like an application problem. Renaming means either replacing the instance or a one-off
# `systemctl disable --now` / re-enable performed over SSH, in the same change as the workflow edit.
#
# ─── THE UNITS BELOW MOVED TO THE RELEASE LAYOUT ON 2026-09-03 ────────────────────────────────────
# They used to name `/home/ubuntu/app/backend` — one directory, overwritten in place by every
# deploy, with no previous version left on the box and therefore no rollback that was not another
# deploy. They now name `/home/ubuntu/app/current/backend`, where `current` is a symlink to
# `/home/ubuntu/app/releases/<git-sha>`. Rolling back becomes flipping that symlink and restarting:
# seconds, no build, no network.
#
# THIS SCRIPT RUNS AT FIRST BOOT AND NEVER AGAIN, so editing it changes nothing on the box that is
# already running. The live instance is brought to the same configuration by the
# "Prepare the release layout and the systemd units" step in `.github/workflows/deploy-backend.yml`,
# which installs the identical paths and limits as systemd DROP-INS
# (`/etc/systemd/system/fieldrepo.service.d/10-release-layout.conf` and `memory.conf`, and the same
# pair for `fieldrepo-queue`) rather than rewriting these base units — because the base units on a
# box provisioned months ago may carry hand edits that a rewrite would silently discard.
#
# THE TWO ARE REDUNDANT ON PURPOSE AND ARE NOT ALLOWED TO DISAGREE. A REBUILT box gets the right
# paths from here even before its first deploy; the LIVE box gets them from the drop-ins. Change a
# directive in one and change it in the other in the same commit — the failure mode of letting them
# drift is a rebuilt instance that behaves differently from the one it replaced, which is the
# hardest kind of difference to see.
#
# nginx needs no change for any of this: it proxies to 127.0.0.1:8000 and knows nothing about where
# on disk the process answering there was started from. Its site file below is unchanged.
set -euxo pipefail

# --- swap (protects the 1 GiB box during pip/prisma installs) ----------------
if [ ! -f /swapfile ]; then
  fallocate -l 2G /swapfile
  chmod 600 /swapfile
  mkswap /swapfile
  swapon /swapfile
  echo '/swapfile none swap sw 0 0' >> /etc/fstab
fi

export DEBIAN_FRONTEND=noninteractive
apt-get update -y
apt-get install -y python3.12-venv python3-pip git ffmpeg nginx

# --- nginx reverse proxy: 80 -> 127.0.0.1:8000 -------------------------------
cat > /etc/nginx/sites-available/fieldrepo <<'NGINX'
server {
    listen 80 default_server;
    server_name _;
    client_max_body_size 200M;

    location / {
        proxy_pass http://127.0.0.1:8000;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_read_timeout 300s;
    }
}
NGINX
ln -sf /etc/nginx/sites-available/fieldrepo /etc/nginx/sites-enabled/fieldrepo
rm -f /etc/nginx/sites-enabled/default
nginx -t
systemctl enable nginx
systemctl restart nginx

# --- systemd unit for the API (uvicorn) --------------------------------------
# IMPORTANT: a SINGLE uvicorn process (NOT --workers 2). With >1 worker uvicorn runs a
# multiprocess supervisor that health-pings each worker over a pipe (answered by a daemon thread)
# and SIGKILLs any worker that fails to pong within timeout_worker_healthcheck. On this small,
# CPU-credit-throttled box a heavy transcription chunk (run via asyncio.to_thread) starved that
# pong thread, so the supervisor SIGKILLed the worker mid-job. SIGKILL skips the shutdown hook, so
# the worker's Prisma query-engine subprocess was orphaned (reparented to init) — one orphan per
# kill cycle — until the orphans exhausted the pooler's client ceiling and EVERY DB call (login included)
# returned HTTP 500 while /health (no DB) stayed 200. One process = no supervisor = no SIGKILL loop.
# The media queue runs in its OWN service (fieldrepo-queue, below), so its heavy AI/ffmpeg work is
# never in the request-serving process — that both removes the SIGKILL trigger and keeps responses
# fast (no CloudFront 504). MEDIA_QUEUE_WORKER_ENABLED=false disables the in-process queue here.
#
# ─── AND THAT LINE IS A DEFAULT, NOT A GUARANTEE — CORRECTED 2026-09-03 ───────────────────────────
# The unit below used to claim `Environment=` was "applied AFTER EnvironmentFile so it always wins".
# systemd does not work that way. systemd.exec(5): the files named by EnvironmentFile= are read
# shortly before the process is executed and "settings from these files override settings made with
# Environment=" — WHATEVER THE TEXTUAL ORDER. Order only decides which of several EnvironmentFile=
# lines beats the others. So a `.env` carrying MEDIA_QUEUE_WORKER_ENABLED=true beats this line, here
# and in `.github/workflows/deploy-backend.yml`'s drop-in, and always did.
#
# Harm to date: none, because BACKEND_ENV has never carried the key — luck plus habit. The guarantee
# now lives where it can be made: the deploy workflow's "Refuse a .env that would start a second
# queue drain" step fails the deploy if the secret ever gains a truthy value. `app/core/config.py`
# also defaults the flag to false since 2026-09-03, so an omitted key is off. The line below is
# worth keeping as documentation and as the default for a `.env` that says nothing; it is not worth
# relying on against a `.env` that says something.
cat > /etc/systemd/system/fieldrepo.service <<'UNIT'
[Unit]
Description=Design Prototype Workshop API
After=network.target

[Service]
User=ubuntu
WorkingDirectory=/home/ubuntu/app/current/backend
EnvironmentFile=/home/ubuntu/app/current/backend/.env
# The web process must never run the queue. NOTE: EnvironmentFile= overrides Environment= whatever
# the order (systemd.exec(5)), so this is the DEFAULT for a .env that omits the key, not an override
# of one that sets it. The deploy workflow refuses a .env that sets it true.
Environment=MEDIA_QUEUE_WORKER_ENABLED=false
ExecStart=/home/ubuntu/app/current/backend/.venv/bin/python -m uvicorn app.main:app --host 127.0.0.1 --port 8000 --workers 1
# A SOFT CEILING AND DELIBERATELY NO HARD ONE. 1 GiB of RAM on this box, shared with the queue
# worker, nginx and the kernel. Over MemoryHigh the cgroup is throttled and reclaimed hard, so a
# heavy request gets slower instead of the kernel's OOM killer choosing a victim by badness score —
# which on this box has meant it choosing UVICORN because the QUEUE was the hungry one, and the API
# then 502ing for a reason that appears nowhere in its own log. There is no MemoryMax here on
# purpose: killing the API to save memory is precisely the outcome these limits exist to prevent.
# The queue's unit below is the one that carries a hard stop, because losing the queue is
# recoverable and losing the API is an outage.
MemoryHigh=500M
Restart=always
# 10s (not 3s) between restarts so that IF the process ever does exit while the database's
# pooler is at its client-connection ceiling, restarts don't hammer the pooler
# faster than its connections can drain. (The app also now keeps serving and reconnects to
# the DB in the background instead of exiting, so this is defense-in-depth.)
RestartSec=10
# Reap the whole control group on stop/restart so a Prisma query-engine is never left orphaned.
KillMode=control-group
TimeoutStopSec=20

[Install]
WantedBy=multi-user.target
UNIT

# --- systemd unit for the media-processing queue worker ----------------------
# Runs the transcription/measurement queue in its OWN process (see app/worker.py). Separate from
# uvicorn on purpose: no multiprocess supervisor can SIGKILL it mid-job, and its heavy work never
# competes with request serving. KillMode=control-group reaps its query-engine on restart.
cat > /etc/systemd/system/fieldrepo-queue.service <<'UNIT'
[Unit]
Description=Design Prototype Workshop media queue worker
After=network.target

[Service]
User=ubuntu
WorkingDirectory=/home/ubuntu/app/current/backend
EnvironmentFile=/home/ubuntu/app/current/backend/.env
ExecStart=/home/ubuntu/app/current/backend/.venv/bin/python -m app.worker
# THE ONLY HARD MEMORY STOP ON THIS BOX, and it is on the queue rather than on the API for a
# reason. This process runs ffmpeg and AI transcription; one large audio job can take it past what
# is left of 1 GiB after uvicorn and the kernel, and unbounded it is the thing that triggers the OOM
# killer — which then does not necessarily kill IT. MemoryHigh throttles it first (slow, still
# working); MemoryMax kills it if it keeps climbing, which is survivable: Restart=always brings it
# back and the job is retried. 400M/550M leaves the API's 500M soft ceiling room inside 1 GiB.
# These are a first setting, not a measurement — if real transcription work thrashes here, raise
# MemoryHigh and read the memory line in `systemctl status fieldrepo-queue` rather than guessing
# twice.
MemoryHigh=400M
MemoryMax=550M
Restart=always
RestartSec=10
KillMode=control-group
TimeoutStopSec=30

[Install]
WantedBy=multi-user.target
UNIT

systemctl daemon-reload
# ENABLED, NOT STARTED, and on a fresh box `current` does not exist yet — so a start here would
# fail on a WorkingDirectory that resolves to nothing. That is fine and is the intended order: the
# deploy workflow creates `releases/<sha>/backend`, points `current` at it, and restarts these
# units as its last act. Until then the box has nginx answering 502, which is the honest state of a
# machine that has been provisioned and not yet deployed to.
systemctl enable fieldrepo || true
systemctl enable fieldrepo-queue || true
# `releases/` is created here as well as by the deploy, so a human looking at a freshly built box
# can see the shape the units expect rather than having to infer it from a broken symlink.
mkdir -p /home/ubuntu/app/releases
chown -R ubuntu:ubuntu /home/ubuntu/app

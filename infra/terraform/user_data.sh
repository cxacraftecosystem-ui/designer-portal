#!/usr/bin/env bash
set -euxo pipefail

if [ ! -f /swapfile ]; then
  fallocate -l 2G /swapfile
  chmod 600 /swapfile
  mkswap /swapfile
  swapon /swapfile
  echo '/swapfile none swap sw 0 0' >> /etc/fstab
fi

export DEBIAN_FRONTEND=noninteractive
apt-get update -y
apt-get install -y git ffmpeg nginx libatomic1 curl ca-certificates openssl

cat > /etc/nginx/sites-available/fieldrepo <<'NGINX'
server {
    listen 80 default_server;
    server_name _;
    client_max_body_size 200M;
    include /etc/nginx/snippets/origin-verify.conf;

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
echo 'allow 127.0.0.1; deny all;' > /etc/nginx/snippets/origin-verify.conf
ln -sf /etc/nginx/sites-available/fieldrepo /etc/nginx/sites-enabled/fieldrepo
rm -f /etc/nginx/sites-enabled/default
nginx -t
systemctl enable nginx
systemctl restart nginx

cat > /etc/systemd/system/fieldrepo-api@.service <<'UNIT'
# backend/deploy/blue_green.sh: one instance per loopback port; nginx proxies to one.
[Unit]
Description=Design Prototype Workshop API on port %i
After=network.target

[Service]
User=ubuntu
WorkingDirectory=/home/ubuntu/app/slot-%i/backend
EnvironmentFile=/home/ubuntu/app/slot-%i/backend/.env
Environment=MEDIA_QUEUE_WORKER_ENABLED=false
Environment=MALLOC_ARENA_MAX=2
ExecStart=/home/ubuntu/app/slot-%i/backend/.venv/bin/python -m uvicorn app.main:app --host 127.0.0.1 --port %i --workers 1
MemoryAccounting=yes
MemoryHigh=1000M
Restart=always
RestartSec=10
KillMode=control-group
TimeoutStopSec=20

[Install]
WantedBy=multi-user.target
UNIT

cat > /etc/systemd/system/fieldrepo-queue.service <<'UNIT'
[Unit]
Description=Design Prototype Workshop media queue worker
After=network.target

[Service]
User=ubuntu
WorkingDirectory=/home/ubuntu/app/current/backend
EnvironmentFile=/home/ubuntu/app/current/backend/.env
Environment=MALLOC_ARENA_MAX=2
ExecStart=/home/ubuntu/app/current/backend/.venv/bin/python -m app.worker
# THE ONLY HARD MEMORY STOP ON THIS BOX, and it is on the queue rather than on the API for a reason.
# This process runs ffmpeg and AI transcription; one large audio job can take it past what is left
# of the box's RAM after uvicorn and the kernel, and unbounded it is the thing that triggers the OOM
# killer — which then does not necessarily kill IT. MemoryHigh throttles it first (slow, still
# working); MemoryMax kills it if it keeps climbing, which is survivable: Restart=always brings it
# back and the job is retried.
#
# 800M/1100M since 2026-09-17, the old 400M/550M doubled with the box. STILL NOT A MEASUREMENT, and
# this one has to say so twice: 400M was never observed to be hit — unlike the API's 500M, which was
# (see its unit above, and the vmstat figures on `instance_type` in main.tf). So the API's number
# now comes from a reading and the queue's is still inherited arithmetic. If real transcription work
# thrashes here, read the memory line in `systemctl status fieldrepo-queue` and raise it from THAT
# number rather than doubling again.
#
# The two soft ceilings total 1800M on the t3.small's 1906 MiB of usable RAM, which is the same bet
# the old pair made inside 1 GiB: MemoryHigh bounds one cgroup, it does not reserve memory, and these
# two do not peak together (an easy bet on the t3.medium). MemoryMax here is the one hard stop on the
# box, and it stays on the queue.
MemoryHigh=800M
MemoryMax=1100M
Restart=always
RestartSec=10
KillMode=control-group
TimeoutStopSec=30

[Install]
WantedBy=multi-user.target
UNIT

systemctl daemon-reload
systemctl enable fieldrepo-queue || true
mkdir -p /home/ubuntu/app/releases
chown -R ubuntu:ubuntu /home/ubuntu/app

BOX_PYTHON_VERSION="3.15.2"
BOX_PYTHON_BUILD="20261026"
BOX_PYTHON_URL="https://github.com/astral-sh/python-build-standalone/releases/download/20261026/cpython-3.15.2%2B20261026-x86_64-unknown-linux-gnu-install_only.tar.gz"
BOX_PYTHON_SHA256="08d4ae9024a7cfbd3b70e559241afa2571cd3208d7bd5f96f88e0080dd4092aa"
box_python_home="/opt/cpython/${BOX_PYTHON_VERSION}+${BOX_PYTHON_BUILD}"
if [ ! -e "$box_python_home" ]; then
  install -d -o root -g root -m 0755 /opt/cpython
  stage="$(mktemp -d /opt/cpython/.incoming.XXXXXX)"
  trap 'rm -rf "$stage"' EXIT
  chmod 0755 "$stage"
  curl -fsSL --proto '=https' --tlsv1.2 --retry 5 --retry-delay 10 --retry-all-errors --connect-timeout 30 --max-time 900 -o "$stage/python.tar.gz" "$BOX_PYTHON_URL"
  echo "${BOX_PYTHON_SHA256}  $stage/python.tar.gz" | sha256sum -c -
  tar -xzf "$stage/python.tar.gz" -C "$stage" --no-same-owner
  rm -f "$stage/python.tar.gz"
  "$stage/python/bin/python${BOX_PYTHON_VERSION%.*}" -I -m compileall -q "$stage/python/lib/python${BOX_PYTHON_VERSION%.*}"
  printf '%s\n' "$BOX_PYTHON_SHA256" > "$stage/python/.pbs-sha256"
  printf '%s\n' "$BOX_PYTHON_URL" > "$stage/python/.pbs-url"
  chown -R root:root "$stage/python"
  chmod -R go-w "$stage/python"
  mv -T "$stage/python" "$box_python_home"
  rm -rf "$stage"
  trap - EXIT
fi
"$box_python_home/bin/python${BOX_PYTHON_VERSION%.*}" -I -c 'import sys, platform, ssl, sqlite3, ensurepip, venv; assert platform.python_version() == sys.argv[1], platform.python_version(); print("CPython", platform.python_version(), "from", sys.base_prefix, "|", ssl.OPENSSL_VERSION)' "$BOX_PYTHON_VERSION"

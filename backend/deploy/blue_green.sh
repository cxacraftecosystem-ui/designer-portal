#!/usr/bin/env bash
# Blue/green release of the API on the EC2 box (2026-10-19).
#
# deploy-backend.yml pipes this file over ssh, so the box runs the copy from the commit being
# deployed and needs nothing installed:
#
#   ssh ubuntu@box "NODE_PIN=26.13.0 bash -s -- release <release id>" < backend/deploy/blue_green.sh
#
# On the box itself, as ubuntu:
#
#   bash /home/ubuntu/app/current/backend/deploy/blue_green.sh status
#   bash /home/ubuntu/app/current/backend/deploy/blue_green.sh restart            # after a hand edit of the .env
#   bash /home/ubuntu/app/current/backend/deploy/blue_green.sh rollback [release] # default: the other port's
#
# WHY. The deploy used to flip `current` and restart the API in place, then wait for /health; with
# migrations pending it stopped the API first. The start alone takes minutes on this box, and nginx
# answered 502 for all of it. Now the API runs on one of two loopback ports:
#
#   /home/ubuntu/app/releases/<id>/backend  one tree per deploy attempt, as before
#   /home/ubuntu/app/slot-8000 -> releases/<id>    the release port 8000 runs (and 8001 the same)
#   /home/ubuntu/app/current   -> releases/<id>    the live release: the queue and operators use it
#   fieldrepo-api@<port>.service           the API on that port (this file installs the template)
#
# A release points the idle port's slot at the new release, migrates while the live port serves,
# starts the idle port, waits for /health on it, points nginx at it (a reload, so no connection is
# dropped), and only then flips `current`, stops the old port and restarts the queue. If anything
# fails before the switch, the old port is still serving and the deploy fails with nothing for users
# to notice. The previous release stays behind the other slot, so `rollback` is a start and a switch.
#
# MIGRATIONS RUN WHILE THE OLD RELEASE SERVES. Each release's migrations must therefore keep the
# previous release working (add first, drop in a later release). If `prisma migrate deploy` fails
# three times with the queue stopped, for any reason, the live API is stopped and the migration tried
# five more times, as every deploy with a migration did before this file. The case this is for is a
# pooler with no session left for the schema engine (EMAXCONNSESSION), but the script cannot tell
# that from any other failure, so a bad migration also costs the API until the old port is
# restarted. It is the one path that still has downtime, and it says so in the log.
#
# The first deploy on the old layout (fieldrepo.service on 8000 through `current`) converts it: the
# new release starts on 8001, and the old unit and its drop-ins are removed after the switch.
#
# Every path and command below can be redirected by the environment, which is how
# backend/deploy/test_blue_green.sh runs this file against stubbed systemctl, nginx and curl.
set -euo pipefail

APP="${APP:-/home/ubuntu/app}"
SITE="${NGINX_SITE:-/etc/nginx/sites-available/fieldrepo}"
UNIT_DIR="${UNIT_DIR:-/etc/systemd/system}"
NODE_CACHE="${NODE_CACHE:-$HOME/.cache/prisma-python}"
# Seconds the new port gets to answer /health. The old port serves the whole time, so running out
# costs a re-run, not an outage; ten minutes is twice the slowest start this pipeline has recorded
# and keeps two APIs from sharing the box for longer.
HEALTH_LIMIT="${HEALTH_LIMIT:-600}"
# Seconds between the nginx reload and stopping the old port: requests already sent to it finish.
DRAIN="${DRAIN:-5}"
UNIT=fieldrepo-api
LEGACY=fieldrepo
QUEUE=fieldrepo-queue

say() { printf '%s\n' "$*" >&2; }
die() { say "::error::$*"; exit 1; }
other() { if [ "$1" = 8000 ]; then echo 8001; else echo 8000; fi; }
active() { sudo systemctl is-active --quiet "$1" 2>/dev/null; }

# The port nginx sends traffic to. nginx is the truth, not systemd: whatever it proxies to is live.
live_port() {
  local p
  p="$(sed -nE 's#^[[:space:]]*proxy_pass http://127\.0\.0\.1:([0-9]+);.*#\1#p' "$SITE" | head -n1)"
  case "$p" in 8000|8001) echo "$p" ;; *) die "$SITE proxies to '${p:-nothing}', not 127.0.0.1:8000 or :8001; refusing to guess which API is live" ;; esac
}

# The unit serving the live port: a slot unit, the pre-2026-10-19 fieldrepo.service, or nothing (a
# new box, or an API that is already down).
live_unit() {
  local port="$1"
  if active "$UNIT@$port"; then echo "$UNIT@$port"
  elif [ "$port" = 8000 ] && active "$LEGACY"; then echo "$LEGACY"
  fi
}

install_unit() {
  local tmp
  tmp="$(mktemp)"
  cat > "$tmp" <<UNITFILE
# backend/deploy/blue_green.sh: one instance per loopback port; nginx proxies to one.
[Unit]
Description=Design Prototype Workshop API on port %i
After=network.target

[Service]
User=ubuntu
WorkingDirectory=$APP/slot-%i/backend
EnvironmentFile=$APP/slot-%i/backend/.env
Environment=MEDIA_QUEUE_WORKER_ENABLED=false
Environment=MALLOC_ARENA_MAX=2
ExecStart=$APP/slot-%i/backend/.venv/bin/python -m uvicorn app.main:app --host 127.0.0.1 --port %i --workers 1
MemoryAccounting=yes
MemoryHigh=1000M
Restart=always
RestartSec=10
KillMode=control-group
TimeoutStopSec=20

[Install]
WantedBy=multi-user.target
UNITFILE
  if ! cmp -s "$tmp" "$UNIT_DIR/$UNIT@.service"; then
    sudo install -m 644 "$tmp" "$UNIT_DIR/$UNIT@.service"
    sudo systemctl daemon-reload
    say "installed $UNIT_DIR/$UNIT@.service"
  fi
  rm -f "$tmp"
}

# Atomic: a finished symlink renamed over the old one, never a moment with no link at all.
point() { ln -sfn "$2" "$1.tmp" && mv -Tf "$1.tmp" "$1"; }

migrate() {
  local tree="$1" tries="$2" attempt
  for attempt in $(seq 1 "$tries"); do
    if (cd "$tree" && PATH="$tree/.venv/bin:$PATH" ./.venv/bin/python -m prisma migrate deploy); then return 0; fi
    say "migrate attempt $attempt/$tries failed"
    [ "$attempt" = "$tries" ] || sleep 10
  done
  return 1
}

# Up to HEALTH_LIMIT seconds by the clock (a single try can hang on a busy box), a line every twenty
# seconds so a slow start is visible and the ssh session never goes quiet.
wait_healthy() {
  local port="$1" start now next_note
  start="$(date +%s)"; next_note=$((start + 20))
  while :; do
    if curl -fsS -m 5 "http://127.0.0.1:$port/health" >/dev/null 2>&1; then
      say "port $port answered /health after $(( $(date +%s) - start ))s"; return 0
    fi
    now="$(date +%s)"
    if [ "$now" -ge $((start + HEALTH_LIMIT)) ]; then return 1; fi
    if [ "$now" -ge "$next_note" ]; then
      say "waiting for /health on $port: $((now - start))s of ${HEALTH_LIMIT}s; $UNIT@$port is $(sudo systemctl is-active "$UNIT@$port" 2>/dev/null || true)"
      next_note=$((now + 20))
    fi
    sleep 2
  done
}

# nginx to the new port: a reload, so connections in flight finish on the old one. A config nginx
# refuses, or that does not answer /health through nginx, is put back.
switch_nginx() {
  local port="$1" backup
  backup="$(mktemp)"
  sudo cp "$SITE" "$backup"
  sudo sed -i -E "s#^([[:space:]]*proxy_pass http://127\.0\.0\.1:)[0-9]+;#\1$port;#" "$SITE"
  if [ "$(live_port)" != "$port" ] || ! sudo nginx -t >/dev/null 2>&1; then
    sudo cp "$backup" "$SITE"; rm -f "$backup"
    die "nginx refused the switch to $port; its config is as it was"
  fi
  sudo systemctl reload nginx
  if ! curl -fsS -m 10 -o /dev/null http://127.0.0.1/health; then
    sudo cp "$backup" "$SITE"; rm -f "$backup"
    sudo systemctl reload nginx
    die "nginx did not answer /health after the switch to $port; it was switched back"
  fi
  rm -f "$backup"
  say "nginx now proxies to $port"
}

# Port $1 is healthy. nginx moves to it, the old unit stops, `current` follows.
promote() {
  local port="$1" old_unit="$2" rel
  rel="$(readlink -f "$APP/slot-$port")"
  switch_nginx "$port"
  handed_over=yes
  sudo systemctl enable "$UNIT@$port" >/dev/null 2>&1 || say "::warning::could not enable $UNIT@$port; it will not start after a reboot"
  if [ -n "$old_unit" ] && [ "$old_unit" != "$UNIT@$port" ]; then
    sleep "$DRAIN"
    sudo systemctl stop "$old_unit" || say "::warning::could not stop $old_unit"
    sudo systemctl disable "$old_unit" >/dev/null 2>&1 || true
  fi
  # `current` moves only once the old process has stopped: the old single-port unit's sys.path names
  # /home/ubuntu/app/current/backend, so moving it under a live process would feed it new code.
  point "$APP/current" "$rel"
  # The rollback candidates the prune ranks: a release is one once it has been live. Written after
  # the flip, so a flip that fails marks nothing; a failure is a warning, the release is live.
  touch "$rel/.released" || say "::warning::could not write $rel/.released; the prune may treat it as never live"
  say "current -> $(readlink "$APP/current")"
  # The pre-2026-10-19 single-port unit and its drop-ins, removed once, after its process stopped.
  if [ -f "$UNIT_DIR/$LEGACY.service" ] && ! active "$LEGACY"; then
    sudo systemctl disable "$LEGACY" >/dev/null 2>&1 || true
    sudo rm -rf "$UNIT_DIR/$LEGACY.service" "$UNIT_DIR/$LEGACY.service.d"
    sudo systemctl daemon-reload
    say "removed the old single-port $LEGACY.service and its drop-ins"
  fi
}

# Starts the release behind port $1's slot beside the live one and promotes it when healthy.
# cand, old_unit and the flags are global: the exit trap reads them.
start_and_promote() {
  sudo systemctl reset-failed "$UNIT@$cand" 2>/dev/null || true
  sudo systemctl start "$UNIT@$cand"
  cand_started=yes
  if ! wait_healthy "$cand"; then
    say "$UNIT@$cand did not answer /health within ${HEALTH_LIMIT}s:"
    sudo journalctl -u "$UNIT@$cand" -n 80 --no-pager || true
    die "the new release did not start. nginx still proxies to $(other "$cand"); it was not switched."
  fi
  promote "$cand" "$old_unit"
  sudo systemctl restart "$QUEUE" 2>/dev/null || true
  queue_was=restarted
}

on_exit() {
  local rc=$?
  if [ "$handed_over" != yes ]; then
    if [ "$cand_started" = yes ]; then sudo systemctl stop "$UNIT@$cand" 2>/dev/null || true; fi
    if [ "$old_stopped" = yes ] && [ -n "$old_unit" ]; then
      say "restarting $old_unit, which was stopped for the migration"
      sudo systemctl start "$old_unit" || true
    fi
  fi
  if [ "$queue_was" = yes ]; then sudo systemctl start "$QUEUE" 2>/dev/null || true; fi
  exit "$rc"
}

cmd_release() {
  local id="${1:-}" rel tree live
  [ -n "$id" ] || die "release needs the release id"
  rel="$APP/releases/$id"; tree="$rel/backend"
  [ -x "$tree/.venv/bin/python" ] || die "$tree has no venv; the build steps did not finish"
  [ -s "$tree/.env" ] || die "$tree/.env is missing or empty; the .env steps did not run"
  # THE PRISMA CLI RUNS ON THE NODE "Pin the Node the Prisma CLI runs on" BUILT, and on no other.
  # Checked before anything is touched: a CLI that cannot start is not a migration to wait out.
  export PRISMA_NODEENV_CACHE_DIR="$NODE_CACHE/nodeenv-${NODE_PIN:-unset}"
  export PRISMA_USE_GLOBAL_NODE=false
  export PRISMA_USE_NODEJS_BIN=false
  if [ "$("$PRISMA_NODEENV_CACHE_DIR/bin/node" --version 2>/dev/null)" != "v${NODE_PIN:-unset}" ]; then
    die "the Prisma CLI's pinned Node (v${NODE_PIN:-unset}) is not at $PRISMA_NODEENV_CACHE_DIR. Nothing has been stopped or switched."
  fi

  install_unit
  live="$(live_port)"
  cand="$(other "$live")"
  old_unit="$(live_unit "$live")"
  if [ "$(readlink -f "$APP/slot-$live" 2>/dev/null || true)" = "$(readlink -f "$rel")" ] && [ -n "$old_unit" ]; then
    die "$rel is already what port $live serves; a release id is never reused, so this is the thing to fix"
  fi
  # Something on the idle port is not serving anybody (nginx points elsewhere): a run killed after it
  # started its candidate. Stopped, so this release starts clean.
  if active "$UNIT@$cand"; then
    say "stopping $UNIT@$cand: it is running but nginx does not send it traffic"
    sudo systemctl stop "$UNIT@$cand"
  fi
  point "$APP/slot-$cand" "$rel"
  say "live port $live ($(readlink -f "$APP/current" 2>/dev/null || echo 'nothing')); this release goes to $cand"

  queue_was=no; old_stopped=no; cand_started=no; handed_over=no
  trap 'on_exit' EXIT
  if active "$QUEUE"; then queue_was=yes; fi
  # The queue pauses for the release: it holds pooler sessions the schema engine may need, and memory
  # the second API needs. It starts again on the new release at the end, or the old one on failure.
  sudo systemctl stop "$QUEUE" 2>/dev/null || true

  if ! migrate "$tree" 3; then
    if [ -n "$old_unit" ]; then
      say "::warning::prisma migrate deploy failed three times with $old_unit serving (read the attempts above: this fallback is for a pooler with no free session, EMAXCONNSESSION); stopping it to free its sessions and trying again. The API is down until $UNIT@$cand answers /health."
      sudo systemctl stop "$old_unit" || true
      old_stopped=yes
      sleep 5
    fi
    migrate "$tree" 5 || die "prisma migrate deploy failed. Nothing was switched: nginx still proxies to $live, on the code that matches the schema the database has."
  fi

  start_and_promote
  say "released: nginx -> $cand, current -> $(readlink "$APP/current")"
}

# The release before this one (the other port's) or a named one, started beside the live port and
# switched to when healthy. Safe while the schema has not moved past what that code reads.
cmd_rollback() {
  local live target
  live="$(live_port)"; cand="$(other "$live")"
  if [ -n "${1:-}" ]; then
    target="$APP/releases/$1"
    [ -d "$target" ] || die "no release $1 under $APP/releases (ls -lt $APP/releases lists them)"
    point "$APP/slot-$cand" "$target"
  fi
  target="$(readlink -f "$APP/slot-$cand" 2>/dev/null || true)"
  [ -x "$target/backend/.venv/bin/python" ] || die "port $cand has no runnable release behind it (${target:-none}); name one: rollback <release>"
  [ -s "$target/backend/.env" ] || die "$target/backend/.env is missing"
  old_unit="$(live_unit "$live")"
  queue_was=no; old_stopped=no; cand_started=no; handed_over=no
  trap 'on_exit' EXIT
  if active "$UNIT@$cand"; then sudo systemctl stop "$UNIT@$cand"; fi
  start_and_promote
  say "rolled back: nginx -> $cand ($target)"
}

# The live port and the queue, in place, for a hand edit of the live .env (settings are cached per
# process). This one does stop the API for its start-up; a deploy never does.
cmd_restart() {
  local live unit
  live="$(live_port)"; unit="$(live_unit "$live")"
  [ -n "$unit" ] || unit="$UNIT@$live"
  sudo systemctl restart "$unit"
  sudo systemctl restart "$QUEUE" 2>/dev/null || true
  wait_healthy "$live" || die "$unit did not answer /health within ${HEALTH_LIMIT}s; read: sudo journalctl -u $unit -n 80"
}

cmd_status() {
  local live
  live="$(live_port)"
  echo "nginx proxies to: $live"
  echo "live unit:        $(live_unit "$live")"
  echo "current ->        $(readlink -f "$APP/current" 2>/dev/null || echo missing)"
  for p in 8000 8001; do
    echo "port $p:        $(sudo systemctl is-active "$UNIT@$p" 2>/dev/null || true), $(readlink -f "$APP/slot-$p" 2>/dev/null || echo 'no release')"
  done
}

case "${1:-}" in
  release) shift; cmd_release "$@" ;;
  rollback) shift; cmd_rollback "$@" ;;
  restart) cmd_restart ;;
  status) cmd_status ;;
  *) die "usage: blue_green.sh release <id> | rollback [release] | restart | status" ;;
esac

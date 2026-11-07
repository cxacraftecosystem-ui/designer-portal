#!/usr/bin/env bash
set -euo pipefail

APP="${APP:-/home/ubuntu/app}"
SITE="${NGINX_SITE:-/etc/nginx/sites-available/fieldrepo}"
UNIT_DIR="${UNIT_DIR:-/etc/systemd/system}"
NODE_CACHE="${NODE_CACHE:-$HOME/.cache/prisma-python}"
HEALTH_LIMIT="${HEALTH_LIMIT:-600}"
DRAIN="${DRAIN:-5}"
UNIT=fieldrepo-api
LEGACY=fieldrepo
QUEUE=fieldrepo-queue

say() { printf '%s\n' "$*" >&2; }
die() { say "::error::$*"; exit 1; }
other() { if [ "$1" = 8000 ]; then echo 8001; else echo 8000; fi; }
active() { sudo systemctl is-active --quiet "$1" 2>/dev/null; }

live_port() {
  local p
  p="$(sed -nE 's#^[[:space:]]*proxy_pass http://127\.0\.0\.1:([0-9]+);.*#\1#p' "$SITE" | head -n1)"
  case "$p" in 8000|8001) echo "$p" ;; *) die "$SITE proxies to '${p:-nothing}', not 127.0.0.1:8000 or :8001; refusing to guess which API is live" ;; esac
}

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
ExecStart=$APP/slot-%i/backend/.venv/bin/python -m uvicorn app.main:app --host 127.0.0.1 --port %i --workers 1 --no-proxy-headers
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
  point "$APP/current" "$rel"
  touch "$rel/.released" || say "::warning::could not write $rel/.released; the prune may treat it as never live"
  say "current -> $(readlink "$APP/current")"
  if [ -f "$UNIT_DIR/$LEGACY.service" ] && ! active "$LEGACY"; then
    sudo systemctl disable "$LEGACY" >/dev/null 2>&1 || true
    sudo rm -rf "$UNIT_DIR/$LEGACY.service" "$UNIT_DIR/$LEGACY.service.d"
    sudo systemctl daemon-reload
    say "removed the old single-port $LEGACY.service and its drop-ins"
  fi
}

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
  if active "$UNIT@$cand"; then
    say "stopping $UNIT@$cand: it is running but nginx does not send it traffic"
    sudo systemctl stop "$UNIT@$cand"
  fi
  point "$APP/slot-$cand" "$rel"
  say "live port $live ($(readlink -f "$APP/current" 2>/dev/null || echo 'nothing')); this release goes to $cand"

  queue_was=no; old_stopped=no; cand_started=no; handed_over=no
  trap 'on_exit' EXIT
  if active "$QUEUE"; then queue_was=yes; fi
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

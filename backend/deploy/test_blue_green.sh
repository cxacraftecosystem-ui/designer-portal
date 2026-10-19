#!/usr/bin/env bash
# shellcheck disable=SC2016  # the stub bodies are single-quoted on purpose: they expand when they run
# Runs blue_green.sh against stubbed sudo, systemctl, nginx, curl and journalctl and fake release
# venvs, so the release logic is proved without a box, root or the network. Checks runs it
# ("Deploy scripts").
#
# The stubs keep the services' state as files. After every stop and every nginx reload the systemctl
# stub asks "is the port nginx proxies to still served?" and records a gap when it is not, so the
# zero-downtime claim is asserted, not assumed.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
script="$here/blue_green.sh"
real_sleep="$(command -v sleep)"
passed=0
failed=0

stub() { printf '#!/usr/bin/env bash\n%s\n' "$2" > "$T/bin/$1"; chmod +x "$T/bin/$1"; }

setup() {
  T="$(mktemp -d)"
  echo "$T" > "$TMPDIR_CHECK/T"
  export APP="$T/app" NGINX_SITE="$T/nginx-site" UNIT_DIR="$T/units" NODE_CACHE="$T/node" \
    STATE="$T/state" HEALTH_LIMIT=2 DRAIN=0 REAL_SLEEP="$real_sleep" NODE_PIN=26.11.1
  mkdir -p "$APP/releases" "$UNIT_DIR" "$STATE/active" "$T/bin" "$NODE_CACHE/nodeenv-26.11.1/bin"
  printf '#!/bin/sh\necho v26.11.1\n' > "$NODE_CACHE/nodeenv-26.11.1/bin/node"
  chmod +x "$NODE_CACHE/nodeenv-26.11.1/bin/node"
  cat > "$NGINX_SITE" <<'NGINX'
server {
    listen 80 default_server;
    location / {
        proxy_pass http://127.0.0.1:8000;
    }
}
NGINX
  stub sudo 'export AS_ROOT=1; while [ "${1#-}" != "$1" ]; do shift; done; exec "$@"'
  stub sleep 'exec "$REAL_SLEEP" 0.02'
  stub journalctl 'echo "journal for $*"'
  stub nginx 'exit "${NGINX_T_RC:-0}"'
  cat > "$T/bin/served" <<'SH'
#!/usr/bin/env bash
port="${1:-$(sed -nE 's#^[[:space:]]*proxy_pass http://127\.0\.0\.1:([0-9]+);.*#\1#p' "$NGINX_SITE")}"
[ -e "$STATE/sick-$port" ] && exit 1
[ -e "$STATE/active/fieldrepo-api@$port" ] && exit 0
[ "$port" = 8000 ] && [ -e "$STATE/active/fieldrepo" ] && exit 0
exit 1
SH
  chmod +x "$T/bin/served"
  stub curl 'url="${*: -1}"
port="$(sed -nE "s#^http://127\.0\.0\.1:([0-9]+)/health\$#\1#p" <<< "$url")"
served $port && { echo ok; exit 0; }
exit 7'
  stub systemctl 'echo "systemctl $*" >> "$STATE/log"
q=no; args=()
for a in "$@"; do case "$a" in --quiet) q=yes ;; --no-pager) ;; *) args+=("$a") ;; esac; done
verb="${args[0]}"; unit="${args[1]:-}"
case "$verb" in
  is-active)
    if [ -e "$STATE/active/$unit" ]; then [ "$q" = yes ] || echo active; exit 0; fi
    [ "$q" = yes ] || echo inactive; exit 3 ;;
  start|restart) touch "$STATE/active/$unit" ;;
  stop) rm -f "$STATE/active/$unit" ;;
esac
case "$verb" in stop|reload) served || echo "gap after systemctl $*" >> "$STATE/gaps" ;; esac
exit 0'
  export PATH="$T/bin:$PATH"
}

# A built release, as the workflow's steps leave it: a venv whose python logs what it is asked, and
# a .env. `prisma migrate` fails while $STATE/migrate_failures counts down.
mkrel() {
  local b="$APP/releases/$1/backend"
  mkdir -p "$b/.venv/bin"
  cat > "$b/.venv/bin/python" <<'PY'
#!/usr/bin/env bash
echo "python $* (in $PWD)" >> "$STATE/log"
if [ "${1:-} ${2:-} ${3:-}" = "-m prisma migrate" ]; then
  n="$(cat "$STATE/migrate_failures" 2>/dev/null || echo 0)"
  if [ "$n" -gt 0 ]; then echo $((n - 1)) > "$STATE/migrate_failures"; exit 1; fi
fi
exit 0
PY
  chmod +x "$b/.venv/bin/python"
  echo "RELEASE=$1" > "$b/.env"
}

legacy_box() {
  mkrel r1
  ln -s "$APP/releases/r1" "$APP/current"
  touch "$APP/releases/r1/.released"
  touch "$UNIT_DIR/fieldrepo.service" "$UNIT_DIR/fieldrepo-queue.service"
  mkdir -p "$UNIT_DIR/fieldrepo.service.d" "$UNIT_DIR/fieldrepo-queue.service.d"
  touch "$STATE/active/fieldrepo" "$STATE/active/fieldrepo-queue"
}

release() { bash "$script" release "$1" 2> >(tee -a "$STATE/err" >&2); }
rollback() { bash "$script" rollback "$@" 2> >(tee -a "$STATE/err" >&2); }
port_now() { sed -nE 's#^[[:space:]]*proxy_pass http://127\.0\.0\.1:([0-9]+);.*#\1#p' "$NGINX_SITE"; }
is_up() { [ -e "$STATE/active/$1" ]; }
current_is() { [ "$(readlink -f "$APP/current")" = "$APP/releases/$1" ]; }
no_gaps() { if [ -s "$STATE/gaps" ]; then cat "$STATE/gaps" >&2; return 1; fi; }

# The command must fail. Not `!`: errexit ignores a negated command, so `! x` asserts nothing.
fails() { if "$@"; then echo "expected to fail: $*" >&2; return 1; fi; }

# Each test in its own subshell with errexit on, OUTSIDE any `if` (an `if` would switch errexit off
# for everything it runs, and every assertion below would pass).
check() {
  local rc
  set +e
  ( set -e; "$1" ) > "$TMPDIR_CHECK/out" 2>&1
  rc=$?
  set -e
  rm -rf "$(cat "$TMPDIR_CHECK/T" 2>/dev/null)"
  if [ "$rc" = 0 ]; then passed=$((passed + 1)); echo "ok   $1"
  else failed=$((failed + 1)); echo "FAIL $1"; sed 's/^/     | /' "$TMPDIR_CHECK/out" | tail -n 30; fi
}

t_legacy_conversion() {
  setup; legacy_box; mkrel r2
  release r2
  [ "$(port_now)" = 8001 ]
  is_up fieldrepo-api@8001; fails is_up fieldrepo; is_up fieldrepo-queue
  [ -f "$UNIT_DIR/fieldrepo-api@.service" ]
  grep -q -- '--port %i' "$UNIT_DIR/fieldrepo-api@.service"
  [ ! -e "$UNIT_DIR/fieldrepo.service" ] && [ ! -e "$UNIT_DIR/fieldrepo.service.d" ]
  [ -d "$UNIT_DIR/fieldrepo-queue.service.d" ]                 # the queue's drop-ins stay
  current_is r2; [ -e "$APP/releases/r2/.released" ]
  [ "$(readlink -f "$APP/slot-8001")" = "$APP/releases/r2" ]
  grep -q 'python -m prisma migrate deploy (in .*/releases/r2/backend)' "$STATE/log"
  no_gaps
}

t_second_release_alternates_and_never_gaps() {
  setup; legacy_box; mkrel r2; mkrel r3
  release r2
  release r3
  [ "$(port_now)" = 8000 ]; is_up fieldrepo-api@8000; fails is_up fieldrepo-api@8001
  current_is r3
  [ "$(readlink -f "$APP/slot-8001")" = "$APP/releases/r2" ]  # the previous release stays behind a slot
  no_gaps
}

t_unhealthy_release_leaves_the_old_one_serving() {
  setup; legacy_box; mkrel r2; touch "$STATE/sick-8001"
  fails release r2
  [ "$(port_now)" = 8000 ]; is_up fieldrepo; fails is_up fieldrepo-api@8001; is_up fieldrepo-queue
  current_is r1; [ ! -e "$APP/releases/r2/.released" ]
  [ -e "$UNIT_DIR/fieldrepo.service" ]
  grep -q 'did not answer /health' "$STATE/err"
  no_gaps
}

t_migration_falls_back_to_stopping_the_old_one() {
  setup; legacy_box; mkrel r2; echo 4 > "$STATE/migrate_failures"
  release r2
  [ "$(port_now)" = 8001 ]; is_up fieldrepo-api@8001; fails is_up fieldrepo
  grep -q 'stopping it to free its sessions' "$STATE/err"
  [ -s "$STATE/gaps" ]                                          # the gap detector sees this path's downtime
}

t_failed_migration_restarts_the_old_one() {
  setup; legacy_box; mkrel r2; echo 99 > "$STATE/migrate_failures"
  fails release r2
  [ "$(port_now)" = 8000 ]; is_up fieldrepo; fails is_up fieldrepo-api@8001; is_up fieldrepo-queue
  current_is r1
  grep -q 'migrate deploy failed' "$STATE/err"
}

t_nginx_refusal_keeps_the_old_config() {
  setup; legacy_box; mkrel r2
  export NGINX_T_RC=1
  fails release r2
  unset NGINX_T_RC
  [ "$(port_now)" = 8000 ]; is_up fieldrepo; fails is_up fieldrepo-api@8001; current_is r1
  no_gaps
}

t_missing_node_pin_touches_nothing() {
  setup; legacy_box; mkrel r2
  export NODE_PIN=99.0.0
  fails release r2
  is_up fieldrepo; is_up fieldrepo-queue; [ ! -e "$APP/slot-8001" ]
  fails grep -q 'systemctl stop' "$STATE/log"
}

t_rollback_returns_to_the_other_port() {
  setup; legacy_box; mkrel r2; mkrel r3
  release r2; release r3
  rollback
  [ "$(port_now)" = 8001 ]; is_up fieldrepo-api@8001; fails is_up fieldrepo-api@8000
  current_is r2
  no_gaps
}

t_rollback_to_a_named_release() {
  setup; legacy_box; mkrel r2
  release r2
  rollback r1
  [ "$(port_now)" = 8000 ]; is_up fieldrepo-api@8000; current_is r1
  fails rollback nope
  no_gaps
}

t_restart_restarts_the_live_port_only() {
  setup; legacy_box; mkrel r2
  release r2
  : > "$STATE/log"
  bash "$script" restart 2>>"$STATE/err"
  grep -q 'systemctl restart fieldrepo-api@8001' "$STATE/log"
  fails grep -q 'fieldrepo-api@8000' "$STATE/log"
}

t_fresh_box() {
  setup
  mkdir -p "$APP/releases/bootstrap/backend"; ln -s "$APP/releases/bootstrap" "$APP/current"
  mkrel r1
  release r1
  [ "$(port_now)" = 8001 ]; is_up fieldrepo-api@8001; current_is r1
}

t_release_refuses_a_missing_or_served_release() {
  setup; legacy_box; mkrel r2
  fails release nope
  fails release ""
  release r2
  fails release r2
  [ "$(port_now)" = 8001 ]; is_up fieldrepo-api@8001
}

t_a_stale_candidate_is_stopped_first() {
  setup; legacy_box; mkrel r2
  touch "$STATE/active/fieldrepo-api@8001"
  release r2
  grep -q 'stopping fieldrepo-api@8001: it is running but nginx does not send it traffic' "$STATE/err"
  [ "$(port_now)" = 8001 ]
}

# The workflow's "Prune old releases" step, run as written (only APP redirected). An `rm` stub refuses
# a *rootowned* path unless it runs under sudo, and an *undeletable* one always.
prune_step() {
  local wf="$here/../../.github/workflows/deploy-backend.yml" real_rm
  real_rm="$(command -v rm)"
  stub rm 'for a in "$@"; do case "$a" in *undeletable*) echo "rm: $a: Operation not permitted" >&2; exit 1 ;; *rootowned*) [ -n "${AS_ROOT:-}" ] || { echo "rm: $a: Permission denied" >&2; exit 1; } ;; esac; done
exec '"$real_rm"' "$@"'
  sed -n '/^      - name: Prune old releases$/,/^          REMOTE$/p' "$wf" \
    | sed -n "/<<'REMOTE'\$/,/^          REMOTE\$/p" | sed '1d;$d' | sed 's/^          //' \
    | sed "s#^APP=/home/ubuntu/app\$#APP='$APP'#" > "$T/prune.sh"
  grep -q "^APP='$APP'\$" "$T/prune.sh"
  bash "$T/prune.sh"
}

# Five releases that went live, oldest first, `current` on the newest, and the oldest behind a slot.
five_releases() {
  local i=0 r
  for r in "$@"; do
    i=$((i + 1)); mkrel "$r"
    touch -d "2026-10-0$i 12:00" "$APP/releases/$r/.released"
  done
  ln -sfn "$APP/releases/$5" "$APP/current"
  ln -sfn "$APP/releases/$1" "$APP/slot-8000"
}

t_prune_keeps_three_current_and_the_slots() {
  setup
  five_releases a1 a2 a3 a4 a5
  prune_step
  [ -d "$APP/releases/a1" ]                                   # behind slot-8000: the rollback target
  [ ! -e "$APP/releases/a2" ]
  [ -d "$APP/releases/a3" ] && [ -d "$APP/releases/a4" ] && [ -d "$APP/releases/a5" ]
}

t_prune_deletes_a_root_owned_release_with_sudo() {
  setup
  five_releases a1 a2-rootowned a3 a4 a5
  prune_step > "$T/out"
  [ ! -e "$APP/releases/a2-rootowned" ]
  grep -q 'with sudo' "$T/out"
}

t_prune_warns_and_succeeds_when_it_cannot_delete() {
  setup
  five_releases a1 a2-undeletable a3 a4 a5
  prune_step > "$T/out"
  [ -d "$APP/releases/a2-undeletable" ]
  grep -q '::warning::could not delete releases/a2-undeletable' "$T/out"
}

# The template the script installs is the one a new box's user_data writes, byte for byte.
t_user_data_writes_the_same_unit() {
  local from_script from_user_data
  from_script="$(sed -n '/<<UNITFILE$/,/^UNITFILE$/p' "$script" | sed '1d;$d' | sed 's#\$APP#/home/ubuntu/app#g')"
  from_user_data="$(sed -n "/fieldrepo-api@.service <<'UNIT'\$/,/^UNIT\$/p" "$here/../../infra/terraform/user_data.sh" | sed '1d;$d')"
  [ -n "$from_script" ]
  [ "$from_script" = "$from_user_data" ] || { diff <(echo "$from_script") <(echo "$from_user_data") >&2; return 1; }
}

TMPDIR_CHECK="$(mktemp -d)"
trap 'rm -rf "$TMPDIR_CHECK"' EXIT
for t in $(declare -F | awk '{print $3}' | grep '^t_'); do check "$t" "$t"; done
echo "$passed passed, $failed failed"
[ "$failed" = 0 ]

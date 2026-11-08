#!/usr/bin/env bash
set -euo pipefail

if [ "$#" -lt 6 ] || [ "$#" -gt 7 ]; then
  echo "usage: $0 OWNER/REPO TAG NAME OUT SHA256 SIZE [ASSET_ID]" >&2
  exit 2
fi
repo="$1"; tag="$2"; name="$3"; out="$4"; sha="$5"; size="$6"; asset_id="${7:-}"
api="https://api.github.com/repos/$repo"
token="${GH_TOKEN:-${GITHUB_TOKEN:-}}"
auth=()
[ -n "$token" ] && auth=(-H "Authorization: Bearer $token")

mkdir -p "$(dirname "$out")"
rm -f "$out"

verified() {
  [ -f "$out" ] && [ "$(stat -c%s "$out")" = "$size" ] && echo "$sha  $out" | sha256sum -c --quiet - 2>/dev/null
}

asset_by_name() {
  local release page ids
  release="$(curl -fsSL --retry 3 --retry-delay 5 "${auth[@]}" -H "Accept: application/vnd.github+json" "$api/releases/tags/$tag" | jq -r '.id')"
  for page in $(seq 1 30); do
    ids="$(curl -fsSL --retry 3 --retry-delay 5 "${auth[@]}" -H "Accept: application/vnd.github+json" \
      "$api/releases/$release/assets?per_page=100&page=$page" | jq -r --arg n "$name" '(.[] | select(.name == $n) | .id), (if length == 0 then "end" else empty end)')"
    case "$ids" in
      end) return 1 ;;
      "") continue ;;
      *) echo "$ids" | head -1; return 0 ;;
    esac
  done
  return 1
}

if curl -fsSL --retry 3 --retry-delay 5 -o "$out" "https://github.com/$repo/releases/download/$tag/$name" && verified; then
  echo "$name: fetched from the release download URL"
  exit 0
fi
echo "::warning::$name: the release download URL failed; fetching through the REST asset endpoint"
rm -f "$out"
[ -n "$asset_id" ] || asset_id="$(asset_by_name)" || { echo "::error::$name is not an asset of $repo@$tag"; exit 1; }
curl -fsSL --retry 3 --retry-delay 5 "${auth[@]}" -H "Accept: application/octet-stream" -o "$out" "$api/releases/assets/$asset_id"
echo "$sha  $out" | sha256sum -c -
test "$(stat -c%s "$out")" = "$size"
echo "$name: fetched from the REST asset endpoint"

#!/usr/bin/env bash
# Releases server, Android and desktop together as version N (tag vN).
# N is the number in VERSION; if vN is already tagged, VERSION is raised by one and committed first.
# Run on an up-to-date main: scripts/release.sh
set -euo pipefail
cd "$(dirname "$0")/.."

[[ "$(git branch --show-current)" == main ]] || { echo "Not on main." >&2; exit 1; }
[[ -z "$(git status --porcelain)" ]] || { echo "Working tree not clean." >&2; exit 1; }
git fetch --quiet --tags origin
[[ "$(git rev-parse HEAD)" == "$(git rev-parse origin/main)" ]] || { echo "main differs from origin/main." >&2; exit 1; }

n=$(cat VERSION)
if git rev-parse -q --verify "refs/tags/v$n" > /dev/null; then
  n=$((n + 1))
  echo "$n" > VERSION
  git commit --quiet -m "chore(release): v$n" -- VERSION
fi

git tag -a "v$n" -m "Notes $n"
git push --quiet origin main "v$n"
echo "Released v$n"

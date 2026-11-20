#!/usr/bin/env bash
# Compatibility gate (docs/decisions.md, spec 5.2):
#  1. api/openapi.json has no breaking changes compared to the last released server (tag server-v*).
#  2. Published contract fixtures were not modified or deleted (only additions allowed).
set -euo pipefail
cd "$(git rev-parse --show-toplevel)"

base_tag=$(git tag --list 'server-v*' --sort=-v:refname | grep -v "^${GITHUB_REF_NAME:-__none__}$" | head -n1 || true)
if [[ -z "$base_tag" ]]; then
  echo "No released server version (server-v*) yet – nothing to compare against."
  exit 0
fi
echo "Comparing against $base_tag"

tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT

if git cat-file -e "$base_tag:api/openapi.json" 2>/dev/null; then
  git show "$base_tag:api/openapi.json" > "$tmp/base.json"
  cp api/openapi.json "$tmp/current.json"
  docker run --rm -v "$tmp:/specs:ro" tufin/oasdiff:${OASDIFF_VERSION:-latest} \
    breaking /specs/base.json /specs/current.json --fail-on ERR
fi

changed=$(git diff --name-status "$base_tag" -- api/fixtures | grep -v '^A' || true)
if [[ -n "$changed" ]]; then
  echo "Published fixtures must never change – only add new ones:"
  echo "$changed"
  exit 1
fi
echo "API compatible, fixtures unchanged."

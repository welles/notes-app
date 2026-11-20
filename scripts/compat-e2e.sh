#!/usr/bin/env bash
# Cross-version sync test: runs the client sync engine of <clients-ref> against the server of <server-ref>.
#   scripts/compat-e2e.sh <server-ref> <clients-ref>      refs: a git tag/commit, or "current"
# Needs: dotnet (global.json), JDK 21. Desktop-only Gradle build, no Android SDK.
set -euo pipefail
root="$(git rev-parse --show-toplevel)"
server_ref="$1"; clients_ref="$2"
work="$(mktemp -d)"
port="${COMPAT_PORT:-5089}"
cleanup() { [[ -n "${server_pid:-}" ]] && kill "$server_pid" 2>/dev/null || true; git -C "$root" worktree prune; rm -rf "$work"; }
trap cleanup EXIT

checkout() { # ref -> dir
  if [[ "$1" == "current" ]]; then echo "$root"; else git -C "$root" worktree add --detach "$work/$1" "$1" >/dev/null 2>&1; echo "$work/$1"; fi
}

server_dir="$(checkout "$server_ref")"
clients_dir="$(checkout "$clients_ref")"

if ! grep -rq "RealServerSyncTest" "$clients_dir/clients/app/src" 2>/dev/null; then
  echo "Clients at $clients_ref have no end-to-end test – skipping."; exit 0
fi

echo "== Server: $server_ref, Clients: $clients_ref"
( cd "$server_dir/server" && dotnet build Notes.Server -c Release -p:OpenApiGenerateDocuments=false -v q )
ConnectionStrings__Default="Data Source=$work/compat.db" CloudflareAccess__Enabled=false \
  Notes__DataProtectionKeysPath="$work/keys" ASPNETCORE_URLS="http://127.0.0.1:$port" \
  dotnet "$server_dir/server/Notes.Server/bin/Release/net10.0/Notes.Server.dll" > "$work/server.log" 2>&1 &
server_pid=$!
for _ in $(seq 1 60); do curl -sf "http://127.0.0.1:$port/healthz" >/dev/null && break; sleep 1; done

( cd "$clients_dir/clients" && ./gradlew -Pnotes.desktopOnly=true :app:desktopTest --tests '*RealServerSyncTest*' \
    -Pnotes.e2eServer="http://127.0.0.1:$port" --console=plain )
echo "== OK: server $server_ref ↔ clients $clients_ref"

#!/usr/bin/env bash
# Online backup of the Notes SQLite database (consistent while the server runs).
# Usage: scripts/backup.sh [source.db] target.db
set -euo pipefail
if [[ $# -eq 1 ]]; then src=/data/notes.db; dst=$1; else src=$1; dst=$2; fi
sqlite3 "$src" "VACUUM INTO '$dst'"
echo "Backup written to $dst"

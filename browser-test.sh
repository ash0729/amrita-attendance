#!/usr/bin/env bash
# End-to-end test of the browser feedback helper against the local mock portal. Usage: ./browser-test.sh [port]
set -euo pipefail
cd "$(dirname "$0")"
PORT="${1:-8765}"
python3 -I browser/build-bookmarklet.py
python3 -I test/mock-portal.py "$PORT" >/dev/null &
MOCK=$!
trap 'kill $MOCK 2>/dev/null || true' EXIT
for _ in $(seq 50); do curl -fs "http://localhost:$PORT/__state" >/dev/null 2>&1 && break; sleep 0.1; done
node test/feedback-helper.test.mjs "$PORT"

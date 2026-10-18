#!/usr/bin/env bash
# Shows a QR code that connects the ProxAI phone app to the proxai Hermes profile.
# Scan it with the phone camera; ProxAI asks you to confirm, then fills in URL + key.
# The QR contains your API key — don't screenshot or share it.
set -euo pipefail

ENV_FILE="${HERMES_ENV:-$HOME/.hermes/profiles/proxai/.env}"

URL="${1:-}"
if [[ -z "$URL" ]]; then
  HOST="$(tailscale status --self --json | python3 -c 'import json,sys; print(json.load(sys.stdin)["Self"]["DNSName"].rstrip("."))')"
  URL="https://${HOST}"
fi

# dotenv rules: value after '=', stop at ' #' comment, trim whitespace and surrounding quotes.
KEY="$(python3 - "$ENV_FILE" <<'EOF'
import re, sys
for line in open(sys.argv[1], encoding="utf-8"):
    if line.startswith("API_SERVER_KEY="):
        v = line.split("=", 1)[1].strip()
        if v[:1] in "\"'" and v[-1:] == v[:1]:
            v = v[1:-1]
        else:
            v = re.split(r"\s+#", v, maxsplit=1)[0].strip()
        print(v)
        break
EOF
)"
if [[ -z "$KEY" ]]; then
  echo "No API_SERVER_KEY found in $ENV_FILE" >&2
  exit 1
fi

# Check the key works before showing it.
code="$(curl -s -o /dev/null -w '%{http_code}' -H "Authorization: Bearer ${KEY}" http://127.0.0.1:8642/v1/models || true)"
if [[ "$code" != "200" ]]; then
  echo "Warning: Hermes rejected this key locally (HTTP $code)." >&2
fi

LINK="$(python3 -c 'import sys, urllib.parse as u; print("proxai://connect?url=" + u.quote(sys.argv[1], safe="") + "&key=" + u.quote(sys.argv[2], safe=""))' "$URL" "$KEY")"

qrencode -t ansiutf8 -m 2 "$LINK"
echo
echo "Scan with the phone camera → open in ProxAI → Connect."
echo "Server: $URL"

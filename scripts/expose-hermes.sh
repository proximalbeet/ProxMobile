#!/usr/bin/env bash
# Makes the proxai Hermes API server (127.0.0.1:8642) reachable from your phone over Tailscale,
# with an HTTPS URL only devices on your tailnet can reach. Hermes's own config is untouched.
#
#   ./scripts/expose-hermes.sh          # turn on
#   ./scripts/expose-hermes.sh off      # turn off
set -euo pipefail

PORT="${HERMES_API_PORT:-8642}"

if [[ "${1:-}" == "off" ]]; then
  tailscale serve --https=443 off
  echo "Stopped serving Hermes over Tailscale."
  exit 0
fi

if ! curl -fsS "http://127.0.0.1:${PORT}/health" >/dev/null; then
  echo "Hermes API server isn't answering on 127.0.0.1:${PORT}." >&2
  echo "Check: systemctl --user status hermes-gateway-proxai" >&2
  exit 1
fi

# If this fails with "access denied", run once: sudo tailscale set --operator=$USER
tailscale serve --bg --https=443 "http://127.0.0.1:${PORT}"

HOST="$(tailscale status --self --json | python3 -c 'import json,sys; print(json.load(sys.stdin)["Self"]["DNSName"].rstrip("."))')"
echo
echo "Hermes is now at:  https://${HOST}"
echo "Put that URL in ProxAI → Settings → Server URL (phone must be on Tailscale)."

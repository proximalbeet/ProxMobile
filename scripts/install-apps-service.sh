#!/usr/bin/env bash
# Installs the ProxAI apps server (APK list + downloads for ProxMobile's Apps tab) as a
# socket-activated systemd user service on 127.0.0.1:8654, and publishes it on the tailnet at
# https://<desktop>.ts.net/apps. Read-only: it serves what Hermes's proxai_app tool built in
# ~/dev/proxai/apps (docs/PROXAI-APPS.md).
#   ./scripts/install-apps-service.sh          # install
#   ./scripts/install-apps-service.sh remove   # stop + uninstall
set -euo pipefail

SERVICE=proxai-apps.service
SOCKET=proxai-apps.socket
REPO="$(cd "$(dirname "$0")/.." && pwd)"
DIR="$HOME/.config/systemd/user"

if [[ "${1:-}" == "remove" ]]; then
  tailscale serve --bg --https=443 --set-path /apps off 2>/dev/null || true
  systemctl --user disable --now "$SOCKET" 2>/dev/null || true
  systemctl --user stop "$SERVICE" 2>/dev/null || true
  rm -f "$DIR/$SOCKET" "$DIR/$SERVICE"
  systemctl --user daemon-reload
  echo "Removed $SOCKET and $SERVICE."
  exit 0
fi

if [[ "$REPO" != "$HOME/dev/projects/ProxMobile" ]]; then
  echo "Run this from the main checkout (~/dev/projects/ProxMobile); the service points there." >&2
  exit 1
fi

mkdir -p "$HOME/dev/proxai/apps" "$HOME/.local/state/proxai/context" "$HOME/.local/state/proxai/notices" "$DIR"   # context: written by Hermes (proxai-context)
install -m 0644 "$REPO/server/$SERVICE" "$DIR/$SERVICE"
install -m 0644 "$REPO/server/$SOCKET" "$DIR/$SOCKET"
systemctl --user daemon-reload
systemctl --user enable "$SOCKET"
systemctl --user restart "$SOCKET"
systemctl --user stop "$SERVICE" 2>/dev/null || true   # next request starts the updated code
curl -fsS --retry 10 --retry-connrefused --retry-delay 1 --max-time 20 http://127.0.0.1:8654/health && echo
tailscale serve --bg --https=443 --set-path /apps "http://127.0.0.1:8654"
echo "ProxAI apps server is ready at https://$(tailscale status --json | python3 -c 'import json,sys;print(json.load(sys.stdin)["Self"]["DNSName"].rstrip("."))')/apps"
echo "Logs: journalctl --user -u $SERVICE -f"

#!/usr/bin/env bash
# Installs and starts the Kanye voice service for the phone app (systemd user service).
#   ./scripts/install-voice-service.sh          # install + start (starts at boot from then on)
#   ./scripts/install-voice-service.sh remove   # stop + uninstall
set -euo pipefail

NAME=proxai-phone-voice.service
REPO="$(cd "$(dirname "$0")/.." && pwd)"
DEST="$HOME/.config/systemd/user/$NAME"

if [[ "${1:-}" == "remove" ]]; then
  systemctl --user disable --now "$NAME" 2>/dev/null || true
  rm -f "$DEST"
  systemctl --user daemon-reload
  echo "Removed $NAME."
  exit 0
fi

if [[ "$REPO" != "$HOME/dev/projects/ProxMobile" ]]; then
  echo "Run this from the main checkout (~/dev/projects/ProxMobile); the service points there." >&2
  exit 1
fi

mkdir -p "$(dirname "$DEST")"
install -m 0644 "$REPO/server/$NAME" "$DEST"
systemctl --user daemon-reload
systemctl --user enable --now "$NAME"

# Wait for it to answer (it starts in a couple of seconds; the model loads on first use).
curl -fsS --retry 20 --retry-connrefused --retry-delay 1 http://127.0.0.1:8653/health && echo
echo "Kanye voice service is running. Logs: journalctl --user -u $NAME -f"

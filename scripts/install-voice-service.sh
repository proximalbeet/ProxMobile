#!/usr/bin/env bash
# Installs the Kanye voice for the phone app as a socket-activated systemd user service:
# systemd listens on 127.0.0.1:8653 and starts the server on the first request; the server
# exits after 5 idle minutes, so it uses no memory while you're not talking to it.
#   ./scripts/install-voice-service.sh          # install (listens from boot on)
#   ./scripts/install-voice-service.sh remove   # stop + uninstall
set -euo pipefail

SERVICE=proxai-phone-voice.service
SOCKET=proxai-phone-voice.socket
REPO="$(cd "$(dirname "$0")/.." && pwd)"
DIR="$HOME/.config/systemd/user"

if [[ "${1:-}" == "remove" ]]; then
  systemctl --user disable --now "$SOCKET" 2>/dev/null || true
  systemctl --user disable --now "$SERVICE" 2>/dev/null || true
  rm -f "$DIR/$SOCKET" "$DIR/$SERVICE"
  systemctl --user daemon-reload
  echo "Removed $SOCKET and $SERVICE."
  exit 0
fi

if [[ "$REPO" != "$HOME/dev/projects/ProxMobile" ]]; then
  echo "Run this from the main checkout (~/dev/projects/ProxMobile); the service points there." >&2
  exit 1
fi

# Older installs started the service itself at login; the socket does that job now.
systemctl --user disable "$SERVICE" 2>/dev/null || true
# Stop the running server (if any) so the socket can take over the port.
systemctl --user stop "$SERVICE" 2>/dev/null || true

mkdir -p "$DIR"
install -m 0644 "$REPO/server/$SERVICE" "$DIR/$SERVICE"
install -m 0644 "$REPO/server/$SOCKET" "$DIR/$SOCKET"
systemctl --user daemon-reload
systemctl --user enable "$SOCKET"
# restart (not start) so re-running this after an update picks up a changed socket unit.
systemctl --user restart "$SOCKET"

# A request through the socket proves activation works (starts the server, not the model).
curl -fsS --retry 20 --retry-connrefused --retry-delay 1 --max-time 30 http://127.0.0.1:8653/health && echo
echo "Kanye voice is ready; the server starts on demand and exits after 5 idle minutes."
echo "Logs: journalctl --user -u $SERVICE -f"

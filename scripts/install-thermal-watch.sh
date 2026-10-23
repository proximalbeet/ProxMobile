#!/usr/bin/env bash
# Installs the PC thermal watch as a 24/7 systemd user service (server/proxai_thermal_watch.py):
# GPU/CPU/RAM every second, alerts (shown on the phone by ProxMobile), and an automatic Ollama
# unload when the GPU reads critical. Replaces the temporary watch started from Claude Code.
#   ./scripts/install-thermal-watch.sh          # install + start
#   ./scripts/install-thermal-watch.sh remove   # stop + uninstall
set -euo pipefail
SERVICE=proxai-thermal-watch.service
REPO="$(cd "$(dirname "$0")/.." && pwd)"
DIR="$HOME/.config/systemd/user"

if [[ "${1:-}" == "remove" ]]; then
  systemctl --user disable --now "$SERVICE" 2>/dev/null || true
  rm -f "$DIR/$SERVICE"
  systemctl --user daemon-reload
  echo "Removed $SERVICE."
  exit 0
fi
if [[ "$REPO" != "$HOME/dev/projects/ProxMobile" ]]; then
  echo "Run this from the main checkout (~/dev/projects/ProxMobile); the service points there." >&2
  exit 1
fi
# The temporary watches from 10-22 (transient units) would write the same files.
systemctl --user stop proxai-thermal-watch-night 2>/dev/null || true
systemctl --user stop proxai-thermal-watch 2>/dev/null || true
mkdir -p "$HOME/.local/state/proxai/thermal-watch" "$DIR"
install -m 0644 "$REPO/server/$SERVICE" "$DIR/$SERVICE"
systemctl --user daemon-reload
systemctl --user enable --now "$SERVICE"
sleep 3
python3 -c "import json,os,time;p=os.path.expanduser('~/.local/state/proxai/thermal-watch/latest.json');d=json.load(open(p));print('watching: GPU',d['gpu_c'],'C,',d['gpu_w'],'W | CPU',d['cpu_pkg_c'],'C | updated',round(time.time()-os.path.getmtime(p)),'s ago')"
echo "Logs: journalctl --user -u $SERVICE -f   ·   alerts: ~/.local/state/proxai/thermal-watch/alerts.log"

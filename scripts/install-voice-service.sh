#!/usr/bin/env bash
# Installs Prox's voice relay for the phone app (VOICE-RELAY) as a socket-activated systemd user
# service: systemd listens on 127.0.0.1:8653 and starts server/voice_server.py on the first request;
# it passes the phone's words to ProxAI's voice door and exits after 5 idle minutes.
#   ./scripts/install-voice-service.sh             # install (saves the installed files first)
#   ./scripts/install-voice-service.sh --rollback  # put the saved files back and restart
#   ./scripts/install-voice-service.sh remove      # stop + uninstall
set -euo pipefail

SERVICE=proxai-phone-voice.service
SOCKET=proxai-phone-voice.socket
REPO="$(cd "$(dirname "$0")/.." && pwd)"
DIR="$HOME/.config/systemd/user"
DROPINS="$DIR/$SERVICE.d"
# The hand-installed sandbox drop-in; the unit carries its own sandbox now. 50-proxai-mode.conf
# (written by the mode switch) stays.
OLD_SANDBOX="$DROPINS/40-sandbox.conf"
SAVED="$HOME/.local/state/proxmobile-voice-previous"
DOOR_DIR="$HOME/.local/state/proxai/voice/api"
OLD_SERVER=server/kanye_voice_server.py

print_checks() {
  cat <<'EOF'
Checks (each one only looks):
  1. systemctl --user show proxai-phone-voice -p PrivateNetwork -p DropInPaths
     want: PrivateNetwork=yes, and DropInPaths shows only 50-proxai-mode.conf
  2. curl -s http://127.0.0.1:8653/health
     want: {"status": "ok", "door": true}
     if not: ./scripts/install-voice-service.sh --rollback (and say so)
  3. readlink /proc/$(systemctl --user show -p MainPID --value proxai-phone-voice)/ns/net /proc/self/ns/net
     want: two different net:[...] values (run it right after check 2, while the relay is up)
Logs: journalctl --user -u proxai-phone-voice -f
EOF
}

if [[ "${1:-}" == "remove" ]]; then
  systemctl --user disable --now "$SOCKET" 2>/dev/null || true
  systemctl --user disable --now "$SERVICE" 2>/dev/null || true
  rm -f "$DIR/$SOCKET" "$DIR/$SERVICE"
  systemctl --user daemon-reload
  echo "Removed $SOCKET and $SERVICE (saved copies, if any, stay in $SAVED)."
  exit 0
fi

if [[ "$REPO" != "$HOME/dev/projects/ProxMobile" ]]; then
  echo "Run this from the main checkout (~/dev/projects/ProxMobile); the service points there." >&2
  exit 1
fi

if [[ "${1:-}" == "--rollback" ]]; then
  if [[ ! -f "$SAVED/$SERVICE" ]]; then
    echo "Nothing saved in $SAVED, so there is nothing to roll back to." >&2
    exit 1
  fi
  systemctl --user stop "$SERVICE" 2>/dev/null || true
  mkdir -p "$DIR"
  install -m 0644 "$SAVED/$SERVICE" "$DIR/$SERVICE"
  if [[ -f "$SAVED/$SOCKET" ]]; then
    install -m 0644 "$SAVED/$SOCKET" "$DIR/$SOCKET"
  fi
  if [[ -f "$SAVED/40-sandbox.conf" ]]; then
    mkdir -p "$DROPINS"
    install -m 0644 "$SAVED/40-sandbox.conf" "$OLD_SANDBOX"
  fi
  # The old unit runs the old server, which VOICE-RELAY took out of the repo: put the saved copy
  # back (it shows as an untracked file; delete it once the relay works again).
  if [[ -f "$SAVED/kanye_voice_server.py" && ! -e "$REPO/$OLD_SERVER" ]]; then
    install -m 0644 "$SAVED/kanye_voice_server.py" "$REPO/$OLD_SERVER"
    echo "Put the old $OLD_SERVER back (untracked)."
  fi
  systemctl --user daemon-reload
  systemctl --user restart "$SOCKET"
  echo "Rolled back to the files saved in $SAVED."
  echo "Check: curl -s http://127.0.0.1:8653/health"
  exit 0
fi

if [[ -n "${1:-}" ]]; then
  echo "Unknown option: $1 (use --rollback or remove, or nothing to install)." >&2
  exit 2
fi

# 1. Save what is installed now, before anything changes. Only once: when the installed unit is
#    already this one (a re-run), the saved pre-relay copies are kept as they are.
mkdir -p -m 700 "$SAVED"
chmod 700 "$SAVED"
if [[ -f "$DIR/$SERVICE" ]] && ! cmp -s "$DIR/$SERVICE" "$REPO/server/$SERVICE"; then
  cp -p "$DIR/$SERVICE" "$SAVED/$SERVICE"
  if [[ -f "$DIR/$SOCKET" ]]; then
    cp -p "$DIR/$SOCKET" "$SAVED/$SOCKET"
  fi
  if [[ -f "$OLD_SANDBOX" ]]; then
    cp -p "$OLD_SANDBOX" "$SAVED/40-sandbox.conf"
  fi
  # The old unit runs kanye_voice_server.py, which is no longer in the repo: keep a copy (from git
  # when the file is already gone) so --rollback has something to run.
  if grep -q kanye_voice_server.py "$SAVED/$SERVICE" && [[ ! -f "$SAVED/kanye_voice_server.py" ]]; then
    if [[ -f "$REPO/$OLD_SERVER" ]]; then
      cp -p "$REPO/$OLD_SERVER" "$SAVED/kanye_voice_server.py"
    else
      gone="$(git -C "$REPO" rev-list -n 1 HEAD -- "$OLD_SERVER" 2>/dev/null || true)"
      if [[ -n "$gone" ]] && git -C "$REPO" show "$gone^:$OLD_SERVER" >"$SAVED/kanye_voice_server.py.part" 2>/dev/null; then
        mv "$SAVED/kanye_voice_server.py.part" "$SAVED/kanye_voice_server.py"
      else
        rm -f "$SAVED/kanye_voice_server.py.part"
        echo "Note: couldn't save the old $OLD_SERVER; --rollback would need it back by hand." >&2
      fi
    fi
  fi
  echo "Saved the installed files in $SAVED."
elif [[ -f "$OLD_SANDBOX" && ! -f "$SAVED/40-sandbox.conf" ]]; then
  cp -p "$OLD_SANDBOX" "$SAVED/40-sandbox.conf"
fi

# 2. The voice door's folder, which the unit binds read-only (the bind fails if it's missing).
if [[ ! -d "$DOOR_DIR" ]]; then
  mkdir -p -m 700 "$DOOR_DIR"
fi

# 3. The unit carries its own sandbox now: the hand-installed drop-in goes (it was saved above).
rm -f "$OLD_SANDBOX"

# 4. Install the unit and socket (copies, not links) and let the socket take over the port.
# Older installs started the service itself at login; the socket does that job now.
systemctl --user disable "$SERVICE" 2>/dev/null || true
systemctl --user stop "$SERVICE" 2>/dev/null || true
mkdir -p "$DIR"
install -m 0644 "$REPO/server/$SERVICE" "$DIR/$SERVICE"
install -m 0644 "$REPO/server/$SOCKET" "$DIR/$SOCKET"
systemctl --user daemon-reload
systemctl --user enable "$SOCKET"
# restart (not start) so re-running this after an update picks up a changed socket unit.
systemctl --user restart "$SOCKET"

echo "Prox's voice relay is installed; it starts on the first request and exits after 5 idle minutes."
print_checks

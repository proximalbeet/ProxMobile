# ProxAI for Android

An Android front end for the **proxai** Hermes Agent profile:

- **Chat** with Hermes, with live streaming and chips showing which tools it's running.
- **Power button**: ProxAI registers as Android's *Default digital assistant*, so
  press-and-hold power (or the corner-swipe assist gesture) opens a voice overlay over
  whatever you're doing. It starts listening right away, sends what you said to Hermes,
  and reads the answer aloud. Works from the lock screen. There's also an **"Ask Hermes"
  Quick Settings tile**.
- **App generation**: ask for an app ("make me a habit tracker") and Hermes returns a
  single-file HTML app. The phone saves it to the **Apps** tab, runs it full-screen, and
  can **pin it to your home screen** as its own icon. **Remix** sends the app back to Hermes
  with your change request; earlier versions are kept on the phone.
- Generated apps can call back into Hermes through a small JS bridge:
  `await ProxAI.ask(prompt)`, `ProxAI.speak(text)`, `ProxAI.toast(text)`, `ProxAI.vibrate(ms)`.

### New in 0.3.0 (2026-10-22)

- **Speech input fixes.** Short pauses no longer send half a sentence: the app keeps listening,
  joins the pieces, and sends after a quiet pause you pick (Settings → Speech input: right away,
  1.5 s, 3 s (default), 5 s, or only when you tap **Send**). A Send button shows while you talk.
  The recognizer is rebuilt on every mic tap and after any error (it could go dead until the
  phone restarted), falls back to the on-device recognizer after repeated failures, and every
  error is shown and kept in Settings → **Show speech log**. Mishearing: a "words to expect" list
  (recognizer hints, Android 13+), "heard => meant" phrase fixes, and Hermes is told voice text
  may be misheard. Speaking while Hermes is still answering queues the message instead of
  dropping it.
- **Local model in server mode.** Settings → Model: **Auto** (default) uses the PC's local model
  (`qwen3.5:35b-a3b`, provider `prox-ai`) while StormTrooper is in SERVER mode and the cloud
  model otherwise; or force **Cloud** / **Local**. The mode comes from the voice server
  (`/voice/v1/mode`, or `/voice/health` on older servers: server mode keeps the voice loaded for a
  year; personal mode turns it off). No Hermes change: an explicit `provider` makes the API
  server honor the requested model.
- **Reminders and notifications.** "Remind me in 20 minutes to…" → Hermes adds a
  `[[remind: 2026-10-22T17:30 | text]]` tag, the app hides it and sets an exact alarm that posts a
  notification (Snooze 10 min / Done), kept across reboots. Upcoming reminders are listed in
  Settings. A notification also shows when Hermes finishes a reply while the app is closed.
- **Context size, cost and auto-compact.** Each reply shows the model and tokens read; the chat
  header shows the context size and an upper-bound cost for the session. When one reply reads
  more than 60k tokens (40k/100k/off), the app asks Hermes for a summary and continues in a fresh
  session that starts from it; there's also a **Compact** button. **New chat** starts a fully
  fresh Hermes session.

```
 phone                                         desktop (CachyOS)
┌─────────────────────────┐   Tailscale    ┌──────────────────────────────────┐
│ power button → overlay  │   (WireGuard)  │ tailscale serve :443             │
│ chat / apps / settings  │ ─────────────▶ │   → 127.0.0.1:8642 (api_server)  │
│ WebView app runner      │  HTTPS + SSE   │ hermes-gateway-proxai            │
└─────────────────────────┘                └──────────────────────────────────┘
```

It talks to the gateway's OpenAI-compatible endpoint, `POST /v1/chat/completions`
(`stream: true`), with `Authorization: Bearer <API_SERVER_KEY>` and an
`X-Hermes-Session-Id` header. The conversation history lives in the proxai `state.db`, so
each request carries only the newest message.

## Setup

### 1. Desktop: make Hermes reachable from the phone

The API server only listens on `127.0.0.1:8642`. To expose it to your tailnet only, over HTTPS:

```bash
./scripts/expose-hermes.sh      # prints https://desktop.tail773dbb.ts.net
```

(If Tailscale says access denied, run `sudo tailscale set --operator=$USER` once.)
To stop: `./scripts/expose-hermes.sh off`.

Another option is `http://100.117.26.105:8642`, but that needs `API_SERVER_HOST=0.0.0.0` in
the proxai `.env` and a gateway restart. That's why `tailscale serve` is recommended.

### 2. Phone

1. Install Tailscale on the phone and sign in to the same tailnet.
2. Install the latest `ProxAI-<version>.apk` (Taildrop: `tailscale file cp ProxAI-0.3.0.apk nathans-s26:`, or `adb install -r`).
3. Connect the app. Easiest: run `./scripts/phone-qr.sh` on the PC, scan the QR code with the
   phone camera, open it in ProxAI and tap **Connect**, then **Save & test**. Or type the server
   URL and the proxai **API_SERVER_KEY** into **Settings** yourself. The key is the `.env` value
   *including* its `<` `>` but without the trailing `# …` comment.
4. Under **Power button**: allow the microphone, then tap **Open settings** and choose ProxAI as
   the *Digital assistant app*.
5. Set press-and-hold power to open the assistant:
   - Pixel: Settings → System → Gestures → Press & hold power button → Digital assistant
   - Samsung: Settings → Advanced features → Side button → Press and hold (if your One UI
     version offers the digital assistant option)
   - Neither available: add the **Ask Hermes** Quick Settings tile instead.

Speech recognition uses Google's recognizer (or the on-device one). Android points the
"default" recognizer at the assistant app, so ProxAI picks a real recognizer itself.

## Kanye voice

Spoken replies use the same `kanye` voice as Hermes on Discord (Chatterbox voice cloning from
`~/.hermes/profiles/proxai/voice/reference/kanye.wav`). `server/kanye_voice_server.py` runs on the
PC with Hermes's own Chatterbox venv and reuses its `tts_pipeline.py` settings, so the voice is
identical. Hermes's config and voice script are not modified.

- Listens on `127.0.0.1:8653`. Tailscale Serve publishes it at `https://<desktop>.ts.net/voice`
  (tailnet only). Requests need the proxai `API_SERVER_KEY`.
- It only runs while you're using it. systemd holds the port (`proxai-phone-voice.socket`) and
  starts the server on the first request. The model then stays loaded between replies (about
  1–1.5 s per sentence), and after 5 idle minutes the server exits, giving back its ~3.4 GB of
  VRAM (to Ollama) and its RAM. A cold start takes about 9 s; the app hides most of it by asking
  the PC to warm up the moment the power-button overlay opens.
- The app plays a reply sentence by sentence, fetching the next sentence while the current one
  plays. If the PC can't be reached it says so and uses the phone's voice. Switch it off in
  Settings → Voice → **Kanye voice**, and use **Test voice** to hear it.

```bash
./scripts/install-voice-service.sh          # install; listens from boot on, starts on demand
./scripts/install-voice-service.sh remove   # uninstall
journalctl --user -u proxai-phone-voice -f  # logs: one line per spoken piece
```

## Build

The toolchain lives in `~/Android` (JDK 17, SDK 35, no root needed):

```bash
export JAVA_HOME=~/Android/jdk17
./gradlew assembleRelease        # app/build/outputs/apk/release/app-release.apk (~2.6 MB)
./gradlew testDebugUnitTest      # SSE parser, app extraction, TTS cleanup
```

The release build is signed with the debug key so it installs directly. Use a real keystore
before giving it to anyone else.

## Code map

| Path | What |
|---|---|
| `data/HermesClient.kt` | streaming client, tool-progress events, connection test |
| `data/ChatRepository.kt` | one shared conversation (main app + overlay), auto-saves generated apps |
| `data/Prompts.kt` | app-generation rules + voice-mode rules sent as the system message |
| `data/AppStore.kt` | generated-app library (`files/apps/*.html`, older versions kept) |
| `assist/AssistServices.kt` | VoiceInteractionService/Session (power button), stub recognizer, QS tile |
| `ui/AssistActivity.kt` | the voice overlay |
| `ui/AppRunnerActivity.kt` | WebView runner, per-app origin + `window.ProxAI` bridge |
| `voice/SpeechController.kt` | speech-to-text (joined phrases, recovery) and text-to-speech |
| `voice/SpeechFixes.kt` | "heard => meant" fixes, recognizer vocabulary, speech log |
| `data/PcMode.kt` | server/personal mode from the voice server, model route choice |
| `data/Reminders.kt` | reminder tags, storage, alarms |
| `notify/Notifier.kt` | notification channels, reminder + reply notifications, boot receiver |
| `ui/SettingsSections.kt` | Speech input, Model, Notifications, Context settings |

## Security notes

- The API key is stored in app-private storage. Anyone holding it can drive your Hermes agent,
  including its terminal tools, so only expose the API server on your tailnet.
- Generated apps run in a WebView with no file access, and each one gets its own origin.
  They can call Hermes through `ProxAI.ask`, so be careful with remixed apps that load
  third-party scripts from a CDN.

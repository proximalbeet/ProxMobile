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
2. Install `ProxAI-0.1.0.apk` (copy it over, or `adb install -r ProxAI-0.1.0.apk`).
3. Open ProxAI → **Settings**: enter the server URL and the proxai **API_SERVER_KEY**, then tap
   **Save & test**.
4. Under **Power button**: allow the microphone, then tap **Open settings** and choose ProxAI as
   the *Digital assistant app*.
5. Set press-and-hold power to open the assistant:
   - Pixel: Settings → System → Gestures → Press & hold power button → Digital assistant
   - Samsung: Settings → Advanced features → Side button → Press and hold (if your One UI
     version offers the digital assistant option)
   - Neither available: add the **Ask Hermes** Quick Settings tile instead.

Speech recognition uses Google's recognizer (or the on-device one). Android points the
"default" recognizer at the assistant app, so ProxAI picks a real recognizer itself.

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
| `voice/SpeechController.kt` | speech-to-text and text-to-speech |

## Security notes

- The API key is stored in app-private storage. Anyone holding it can drive your Hermes agent,
  including its terminal tools, so only expose the API server on your tailnet.
- Generated apps run in a WebView with no file access, and each one gets its own origin.
  They can call Hermes through `ProxAI.ask`, so be careful with remixed apps that load
  third-party scripts from a CDN.

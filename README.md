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

### New in 0.9.19 (2026-11-18): "Hey Prox" on the phone (WAKE-PHONE)

- Once installed, every notice the PC stamps tier `thread` goes to the chat only and stops buzzing, whatever its kind
  (not just the ones for an app): no notification, nothing on the lock screen. This repo only passes `tier` through;
  which kinds the PC stamps `thread` is decided on the PC (Hermes proxai-schedule), not here.
- Hey Prox: say it and the voice overlay opens (Settings > Voice, off until you turn it on; mute from the Hey Prox tile). The word is heard on the phone only; on the lock screen it follows your app lock, and an OK on your PC always needs your tap. Details and your steps: "Hey Prox (WAKE-PHONE)" below.

### New in 0.9.18 (2026-11-17): approved vault changes, with Undo (VAULT-UNDO-APP)

- ProxMobile's half of VAULT-YES. It polls Prox Dump sync's `GET /vault-yes/written?since=ISO` when it opens, every
  minute while it's open, and about every 15 minutes otherwise (`notify/VaultYesSync.kt`). Each new entry gets one
  notification, `📝 <path> <how> (from your <chat|call|terminal|other>)`. Only the path (escaped, at most 160
  characters) is shown, never the note's text. Unknown approvers read "other" and unknown hows read "changed".
- **Undo** (`POST /vault-yes/<id>/undo`) shows only when the entry says `can_undo` and no other entry shares its id.
  Entries that share an id are listed, marked, and have no Undo. 409 says "the note changed since", 503 says "not set
  up on the PC (log folder missing or over its cap)". Settings → PC links → Approved vault changes lists last week's.
- Every call carries the Prox Dump sync bearer, `X-ProxAI-App: com.proxai.hermes` and `X-ProxAI-Owner-Token`. It
  follows no redirects, and nothing is sent until both tokens are set. Logic and client: `data/VaultYes.kt`.
- VAULT-UNDO-APP+ (security #47, #48, #51): the Undo button needs the phone unlocked (Android 12+); with the app lock
  on and ProxMobile locked it sends nothing and the notification says "Unlock ProxAI to undo". An id is only the PC's
  12 lower-case hex characters; an item's time is kept only if it is an ISO time. A PC notice with `"tier":"thread"`
  (the apps server passes `tier` through) goes to the chat only: no notification, nothing on the lock screen.

### New in 0.9.17 (2026-11-17): Prox's voice through a relay

- The PC's `/voice` server (`server/voice_server.py`, was `kanye_voice_server.py`) only relays to
  ProxAI's voice door: no network, no key, no model of its own. The app plays mp3, says "Prox's
  voice" everywhere, and `ProxAI.voice()` resolves `"pc"` where it resolved `"kanye"`.

### New in 0.7.0 (2026-10-25): Study Coach groundwork

- **No title bars on PC-built apps.** They run full screen with a small floating ⋯ menu in the
  top-right corner (Reload, Stop talking, About). Apps keep that 48x48 corner clear. Quick apps made
  in chat (the in-app runner) keep their title bar (Nathan, 10-25), with a new "Stop talking" item.
- **New app features**, through ProxMobile's provider (`PcStatsProvider`), which now checks each call
  against the features in the calling app's own `proxai-app.json`:
  - `ask`: `await ProxAI.ask(prompt)` from installed apps (was a stub), in a session per app.
  - `voice`: `await ProxAI.voice(text)` speaks in the Kanye voice (`voice/AppVoice.kt`, played by
    ProxMobile) and resolves `"kanye"`, `"phone"` (fallback) or `"stopped"`; `ProxAI.stopVoice()`.
  - `listen` (shell only): `await ProxAI.listen({silenceMs, hints})` → the recognized text (`""` if
    nothing); `ProxAI.stopListening()`. Needs RECORD_AUDIO; the page never gets audio.
  - `files` (shell only): `<input type="file">` opens the system picker.
  - `tutor`: `ProxAI.tutor.*`, the Study Coach (Hermes plugin `proxai-tutor`): notebooks, sources and
    uploads (sent in 192 KB pieces), study sprints, answers, hints, skip, coach questions, plan, overview.
    ProxMobile forwards each call to `<server>/apps/v1/tutor/...`; the apps server forwards it to the
    plugin's Unix socket. Read timeout 150 s (grading takes a few seconds).
  - Also `ProxAI.warmVoice()` (load the Kanye voice on the PC ahead of time) and `ProxAI.keepAwake(on)`.
- Shell apps stop talking and listening when they leave the screen.
- Apps server: `GET|POST /v1/tutor/<route>` (bodies up to 640 KB), and the unit sees
  `~/.local/state/proxai/tutor/api` read-only (the socket). Create that folder before restarting it.

### New in 0.3.2 (2026-10-22)

- Kanye voice "CUDA out of memory" fix. With the local model loaded (server mode) the GPU is
  nearly full, and a 200+ char piece failed, which switched the rest of the reply to the phone
  voice. Pieces are now at most 180 chars; a failed piece is retried once, and only that piece
  falls back to the phone voice. The voice server frees PyTorch's cache and retries a piece in
  two halves when it runs out of GPU memory (needs `systemctl --user restart proxai-phone-voice`
  after merging, since the service runs `server/` from the main checkout).

### New in 0.3.1 (2026-10-22)

- Glowing blue look (was purple), named **ProxAI** everywhere (was Hermes), new "P + spark" logo.
- "thinking…" / "still working…" while a reply is on its way (the local model's reasoning used to
  look like a frozen blank line).
- Hands-free: no error after a reply (waits for the mic, retries a busy recognizer; silence ends
  quietly).
- **What's new** list in the app (pops up once after an update; Settings → What's new), kept in
  `ui/WhatsNew.kt`. Add each release's changes there.

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
- **Model.** Settings → Model: **Cloud** (default, Sonnet 5.5 unless another cloud model is
  picked) or **Local** (the PC's local model, `qwen3.5:35b-a3b` or `qwen3.5:9b`, provider
  `prox-ai`). X1-PM (0.9.14): "Auto" is gone (it sent chat to the local model in server mode; a
  saved Auto reads as Cloud), the app only sends a model from its fixed lists
  (`ModelRoute.CLOUD_CHOICES` / `LOCAL_CHOICES`), and nothing checks the PC's mode before a
  message goes out. An explicit `provider` makes the API server honor the requested model.
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
./scripts/expose-hermes.sh      # prints https://your-desktop.your-tailnet.ts.net
```

(If Tailscale says access denied, run `sudo tailscale set --operator=$USER` once.)
To stop: `./scripts/expose-hermes.sh off`.

Another option is `http://100.x.y.z:8642`, but that needs `API_SERVER_HOST=0.0.0.0` in
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

## Prox's voice

Prox speaks; the phone app only plays it. ProxAI's gateway makes the voice and serves it on its
voice door, a Unix socket in the profile's state folder (`voice/api/voice.sock`, VOICE-DOOR).
`server/voice_server.py` is a thin relay in front of that door (VOICE-RELAY, 2026-11-17):

- Tailscale Serve publishes it at `https://<desktop>.ts.net/voice` (tailnet only). systemd holds
  `127.0.0.1:8653` (`proxai-phone-voice.socket`) and hands the listener to the server on the first
  request; after 5 idle minutes it exits.
- `GET /health` (no token), `GET /v1/mode` → the door's `/mode`, `POST /v1/warmup` → 202 (does
  nothing), `POST /v1/tts {"text"}` → the door's `/speak` → `audio/mpeg`. Each route but `/health`
  needs `Authorization: Bearer …`; the relay passes the phone's header and text to the door as they
  are, and the door's status and error back. The relay builds the door's body itself:
  `{"text": <the phone's text>, "which": "app"}`. `which` is fixed in the relay, and no other field
  the phone sends (its own `which`, `voice`, …) reaches the door. The door checks the key; no door → 503 "ProxAI isn't
  running".
- The relay is standard-library Python run as `/usr/bin/python3 -I`. It holds no key, reads no key
  file, state folder or model, and has no network: the unit (`proxai-phone-voice.service`) sets
  `PrivateNetwork=yes`, `RestrictAddressFamilies=AF_UNIX`, `ProtectHome=tmpfs`, and binds only this
  `server/` folder and the door's folder, read-only.
- The app plays a reply sentence by sentence (mp3), fetching the next while the current one plays.
  If the PC can't be reached, refuses, or sends something that won't play, it uses the phone's
  voice. Switch it off in Settings → Voice → **Prox's voice**; **Test voice** plays a sample.

```bash
./scripts/install-voice-service.sh             # install; saves the old files first
./scripts/install-voice-service.sh --rollback  # put the saved files back
./scripts/install-voice-service.sh remove      # uninstall
journalctl --user -u proxai-phone-voice -f     # logs: one line per request (route, status, ms)
```

## Hey Prox (WAKE-PHONE)

Say "Hey Prox" and the phone opens the same voice overlay as the power-button hold. From there everything works as
it does today. The word is heard **on the phone only**. Settings → Voice → **Listen for "Hey Prox"** is off until you
turn it on.

- **What runs:** a foreground service of type microphone (`voice/WakeWordService.kt`). While the mic is open, Android
  shows its green mic dot. A fixed notification shows the whole time the switch is on: "Listening for “Hey Prox”",
  "“Hey Prox” is muted" or "“Hey Prox” paused" with the reason. It never hides that it's listening.
- **When it doesn't listen:**
  - during a call (the audio mode; also CallWatcher's call state when the Phone permission is granted)
  - while the PC says `desk`
  - at 15 % battery or less and not charging
  - while muted from the **Hey Prox** Quick Settings tile
  - while ProxAI itself uses the mic (the overlay, a dictation, a reply read aloud)
  - while its notification can't show: ProxAI's notifications or its "Listening for “Hey Prox”" channel are off.
    Swipe the notification away and it comes straight back.

  The PC's word is a notice of the fixed kind `desk` or `away` on the feed PcListener long-polls (`/v1/pc/notices`).
  The newest one wins, and these notices are never shown. When the long poll fails, the word is unknown, which counts
  as away. PROX-PC publishes it; until then the phone just listens.
- **On the word:** a short buzz, then the overlay. As the digital assistant, ProxAI opens it through its own assistant
  session, over other apps and the lock screen, with no screenshot and no app name. If ProxAI isn't the assistant
  and is in the background, a "Heard “Hey Prox” · tap to talk" notification shows instead. After the word, speech
  becomes text through the phone's **on-device recognizer only**, never Google's online one. With no on-device
  recognizer, it says so and you type.
- **Sound before the word** goes mic → one reused 0.1 s chunk in memory → the engine, and nowhere else: no file, no
  network, no log. The chunk is zeroed and the engine's own short window is dropped after every wake or stop.
- **A wake permits nothing outward.** It opens the overlay, and every outward step still asks as before. No screen
  reading, no clipboard. A wake-opened overlay is stricter than the power button's (round 2):
  - **OKs need your tap.** Before it sends what you said, it asks your PC (`/v1/approvals`) whether a yes-gate is
    waiting. If one is, nothing is sent: the Accept / Reject sheet shows with "That needs your tap". If the PC can't
    be asked, nothing is sent either: your words wait in the text box for you to tap Send.
  - **The app lock keeps counting.** An untouched wake overlay isn't "on screen" for ProxAI's app lock, so the lock
    still engages on time. A touch, a power-button press, your fingerprint or the PIN in it count as you.
  - **False wakes end quickly.** If nothing is heard after the word, the overlay closes quietly, with no second
    listen. One dictation stops at 40 s. Hands-free listens again only after the reply to a request it sent, and an
    untouched wake overlay does that at most 3 times; after that, only a touch or a new "Hey Prox" makes it listen.
  - **The threshold:** the keywords line has its own threshold (`#0.20`, tuned 11-18 from 0.35; higher wakes less; the engine's default is
    0.25), tuned in your live test.
  - **The race at the word:** when the word arrives, the stop rules are asked again. A call, a mute or "desk" that
    came in meanwhile drops it: no buzz, no overlay.
- **On the lock screen: shape C** (your pick, 11-18). `WakeLockScreen.decide` in `data/Wake.kt` decides it; both
  the listener and the overlay ask it.
  - A wake never restarts ProxAI's app-lock timer.
  - **ProxAI's app lock on** (today): the overlay opens over the lock screen as it does now. Once the app lock's time
    is up, ProxAI's own fingerprint prompt shows. After that unlock it listens at once, and the unlock counts as you
    touching the overlay.
  - **ProxAI's app lock off:** hands-free for 30 min after your last phone unlock. You can pick 10 min, 30 min or
    2 h in Settings → Voice. After that the overlay shows only **Unlock to talk**: one touch asks for your phone's
    unlock, and then it listens.
  - The clock is a real unlock event: Android's "user present", seen by the running listener. It is kept in memory
    only and never stored. If no unlock has been seen since the listener started, it counts as not recently
    unlocked, so you get "Unlock to talk".
  - Whatever it does on the lock screen, the overlay is a wake overlay, so a waiting OK always needs your tap.
- **One log line per wake** in Settings → Show speech log, e.g. `2026-11-18 07:30:12 from=wake done` or
  `… from=wake refused (another device answered)`. It never holds audio or what you said. Settings → Voice shows
  **Wakes today** (a number only), and so does the notification.
- **Both devices heard it:** the first to claim a wake wins for 3 s, and the other beeps once and stays quiet. The PC
  side waits for PROX-PC, so today the phone's claim (`LocalWakeClaim`) always wins.

**Mute it:** add the **Hey Prox** tile (pull down Quick Settings → edit → drag it in). Tap it → *For 1 hour* or
*Until I turn it back on*. Tap a muted tile to listen again. Settings → Voice also has **Listen again** while muted.
On a locked phone, one tap mutes until you turn it back on, with no dialog. Unmuting asks for your phone's unlock
first. When the listener isn't running, the tile says "Open ProxAI to start".

**What you keep and lose.** You gain hands-free voice. Nothing new is permitted by a wake. It costs battery: the mic
and the engine run while it listens, and the CPU stays awake only while the mic is open. After a reboot it starts the
next time you open ProxAI, because Android only lets a microphone service start while the app is on screen.

### Adding the engine (your steps, on the PC)

The engine is **sherpa-onnx 2.0.1** keyword spotting (Apache-2.0) with the English keyword model
`sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01`, both from k2-fsa's own GitHub releases. Neither is in git.
Without them the app builds as before and the switch says the engine isn't in this build.

1. `scripts/wake-engine.sh --print-hashes` downloads both to a temp folder. It prints each file's sha256 and size,
   the model's line from the release's `checksum.txt`, and the engine's licence as GitHub reports it. It installs
   nothing.
2. Compare the AAR's hash with the digest on the v2.0.1 release page, and the model's with `checksum.txt`.
   - The engine's licence must be Apache-2.0 or MIT; nothing else is accepted for the AAR.
   - The model's docs page states no licence. Your pick (11-18, "Add it to prox mobile as a feature") is already in
     the pins: `model_licence=unstated-own-phone`. This value is accepted for the model only.
   - With that value, the APK's NOTICE says so, plus: "This APK is for Nathan's own phone only and never goes to
     anyone else." **This APK stays on your phone:** don't share it, post it or install it for anyone else. If that
     ever needs to change, the model needs a stated licence first.
3. Fill `aar_sha256` and `model_sha256` in `app/wake-engine.sha256`.
4. Once: `scripts/wake-engine.sh --keywords`. It downloads the pinned model to a temp folder and turns
   `HEY PROX :2.0 #0.20 @HEY_PROX` into the model's tokens with sherpa-onnx's own `text2token`. That runs from PyPI in
   a throwaway `uvx` environment (Python 3.12, `sherpa-onnx==2.0.1`, `sentencepiece==0.2.0`, `click==8.1.8`), and
   this is the only step that runs PyPI code. It checks the line and writes
   `app/wake-keywords.txt`, then prints `keywords_sha256=…`.
5. Fill `keywords_sha256` and `engine_licence`, then commit the pins and `app/wake-keywords.txt`.
6. `scripts/wake-engine.sh install`. It runs no PyPI code. It does the following and refuses on any mismatch:
   - first, with no network: every pin is filled, the engine's licence is Apache-2.0 or MIT, the model's is
     Apache-2.0, MIT or unstated-own-phone, and the committed keywords line matches its pin and has `#threshold`
     and `@HEY_PROX`
   - the engine's licence matches what GitHub says; both downloads match their pins
   - picks the model's int8 files, and checks every token of the line is in `tokens.txt`
   - copies the AAR to `app/libs/sherpa-onnx.aar` and the 5 model files (encoder, decoder, joiner, `tokens.txt`,
     `keywords.txt`) to `app/wake/assets/wake/`
   - writes the NOTICE (with the own-phone line for unstated-own-phone) and `app/wake-model.sha256`, the 5 files'
     sha256: commit the sums file
7. Build: `./gradlew assembleRelease`. With the AAR present, the build does the following and fails otherwise:
   - compiles `app/src/wake/` instead of `app/src/nowake/`
   - checks the AAR against `aar_sha256` again and both licences; with unstated-own-phone, also that the NOTICE says
     so and has the own-phone line
   - checks each of the 5 model files against `app/wake-model.sha256`, and the keywords line against
     `keywords_sha256` and its shape
   - bundles the model
   - keeps only arm64 native code

   On the phone, the engine also refuses a keywords line without its own threshold.
8. Compare the APK's permissions with the manifest. The engine's AAR mustn't add any:
   `$HOME/Android/sdk/build-tools/*/aapt2 dump permissions app/build/outputs/apk/release/app-release.apk`
   (the SDK may live elsewhere on your PC). The list should match `app/src/main/AndroidManifest.xml`'s
   `uses-permission` lines and nothing more.
9. Live test, on the phone:
   - Install, open ProxAI, turn the switch on, and allow the mic and notifications.
   - First, Settings → Voice must say **On-device speech recognizer: available**. If it says not available, stop:
     after the word you could only type.
   - Check that the notification and the green mic dot show. Swipe the notification away: it comes back. Turn off
     the "Listening for “Hey Prox”" channel in Android's settings: the dot goes away and Settings says notifications
     are off. Turn it back on.
   - Say "Hey Prox": it buzzes and the overlay opens. Say nothing: it closes on its own.
   - On the lock screen with ProxAI's app lock on (today): it opens. Wait past your app-lock time and say it again:
     ProxAI's fingerprint prompt shows, and after it the overlay listens at once.
   - With ProxAI's app lock off: unlock the phone, lock it, and within the window say "Hey Prox": it listens. Past
     the window (or after a ProxAI restart with no unlock since), it shows "Unlock to talk"; tap, unlock, and it
     listens.
   - Leave a wake conversation hands-free without touching it: it listens again at most 3 times, then stops.
   - With an OK waiting on the PC (ask for something that needs one), say "Hey Prox… yes". Nothing is sent, and the
     sheet shows "That needs your tap".
   - Leave a wake overlay untouched past your app-lock time: ProxAI locks anyway.
   - Mute from the tile: the dot goes away and the notification says muted. Lock the phone and unmute from the tile:
     it asks for your unlock first.
   - Force-stop ProxAI (Android settings → Apps → ProxAI → Force stop) so the listener is dead. Then unmute from the
     tile: it either starts again or says "Open ProxAI to start". It must never listen without the notification.
   - Make a call: it pauses.
   - Tune the threshold if it wakes by itself (raise `#0.20`) or misses you (lower it): run `--keywords` again,
     re-pin, install, build.
   - Check Settings → Show speech log for the `from=wake` lines, and **Wakes today**.

To take the engine out again, delete `app/libs/sherpa-onnx.aar` and `app/wake/` and rebuild.

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
| `data/Wake.kt` | "Hey Prox" rules: the switch, the mutes, the PC's desk word, stop rules, the claim, the log line |
| `voice/WakeEngine.kt` | the engine interface, the mic-to-engine loop (pre-wake sound stays in memory), the wake flow |
| `voice/WakeWordService.kt` | the microphone foreground service and its fixed notification |
| `assist/WakeTileService.kt` | the Hey Prox tile (mute 1 hour / until on again) |
| `src/wake/`, `src/nowake/` | the sherpa-onnx engine (only with the pinned AAR) or none |

## Security notes

- The API key is stored in app-private storage. Anyone holding it can drive your Hermes agent,
  including its terminal tools, so only expose the API server on your tailnet.
- Generated apps run in a WebView with no file access, and each one gets its own origin.
  They can call Hermes through `ProxAI.ask`, so be careful with remixed apps that load
  third-party scripts from a CDN.

## License

[PolyForm Noncommercial 1.0.0](LICENSE.md). You can use, change and share this for noncommercial purposes;
anyone who shares it must keep the license and this notice:

> Required Notice: Copyright (c) 2026 Nathan Kenney (proximalbeet) — https://github.com/proximalbeet/ProxMobile

Commercial use needs written permission from Nathan Kenney.

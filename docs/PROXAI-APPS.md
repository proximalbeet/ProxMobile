# ProxAI apps: how the app generator works

Ask ProxAI for an app on the phone ("make me a tip calculator"). Claude Code builds it on the PC,
inside Hermes's locked-down container, tests it, and turns it into a real Android app (APK). The
app shows up in ProxMobile's **Apps** tab, where you install it, update it, or roll it back.

Checklist: Phase 15 ("MCP app builder"), decisions from 2026-10-22. This file is kept up to date
as each piece lands; see the [changelog](#changelog) at the bottom.

## The flow

```
 phone (ProxMobile)                 PC: Hermes gateway             PC: sandbox container (no internet*)
 ──────────────────                 ──────────────────             ─────────────────────────────────────
 "make me a tip calculator"  ──▶    app plugin picks a name  ──▶   Claude Code (cc-run) in
                                    and starts Claude Code         /workspace/proxai/apps/tip-calc/
                                                                     1. writes www/index.html + proxai-app.json
 Apps tab shows progress     ◀──    status.json                      2. tests it
 (planning → writing →                                               3. proxai-app-build → signed APK
  testing → building → ready)                                        4. git commit + tag per build
 [Install] / [Update]        ◀──    apps server (tailnet only)
   → Android's install prompt       serves the APK
 the app runs on its own
```
\* the container reaches only Anthropic, npm, PyPI and GitHub through the egress proxy; the APK
build itself needs no network at all.

## What each part is

| Part | Where | What it does | Status |
|---|---|---|---|
| **App shell** | ProxMobile `appshell/` | The Android code every generated app runs on: ProxAI title bar (blue gradient), the app full screen, and `window.ProxAI` for phone features. Compiled once into `classes.dex` (`./gradlew :appshell:shellKit`) | ✅ built, 5 tests |
| **Builder** `proxai-app-build` | Hermes `sandbox/image/` (in the image at `/usr/local/bin`) | Checks an app folder, writes its manifest (only the permissions its features need), icon, packs the shell + the app's files, signs it, keeps every version, commits + tags the app's git repo | ✅ built, 6 tests in the real image |
| **Sandbox image** `proxai-sandbox:2.1.302-apps` | Hermes `sandbox/build-image.sh` | The live container image + Java 17, Android build-tools 35, android-35, the shell kit and the builder (copied from `~/Android`, nothing downloaded) | ✅ built; **not live** (config still uses `2.1.302`) |
| **Hermes app plugin** `proxai_app` | Hermes `plugins/proxai-apps/` | create / update / rollback / status / list, phone + PC terminal only. Starts Claude Code in the background (one build at a time) with fixed instructions (`/opt/proxai-shell/GUIDE.md` in the image), returns at once | ✅ built, 12 tests + a real end-to-end build |
| **Apps server** | ProxMobile `server/proxai_apps_server.py` | Read-only: lists apps + progress, serves APKs, API key required; socket-activated, sandboxed (signing key hidden, no network), tailnet `/apps` | ✅ built, 9 tests + sandbox check; **not installed** |
| **Apps tab** (ProxMobile 0.4.0) | ProxMobile `ui/PcAppsSection.kt`, `data/PcApps.kt`, `data/AppInstaller.kt` | "Built on your PC": progress while building, Install / Update / Open, Versions + Roll back, Change with ProxAI; checks SHA-256 and pins the signing key | ✅ built, 33 app tests; APK `~/Downloads/ProxAI-0.4.1.apk` |
| **`ProxAI.ask` for installed apps** | ProxMobile | Lets a generated app ask ProxAI through ProxMobile (which holds the key), only for apps signed with the generated-apps key | ⏳ |

## Tech stack

- **Phone:** ProxMobile (Kotlin, Jetpack Compose, OkHttp), Android 10+ (minSdk 29, target 35).
- **Generated apps:** HTML/CSS/JavaScript written by Claude Code, running in the ProxAI shell:
  Kotlin, plain Android framework classes (`Activity`, `WebView`, `TextToSpeech`, `AlarmManager`,
  notifications), no AndroidX, so each APK is ~630 KB and builds in seconds.
- **Builder:** Python 3 (standard library only) + Android build-tools 35 (`aapt2` to compile the
  manifest/icon and pack assets, `zipalign`, `apksigner`), Java 17 (`keytool`, `apksigner`), git.
  No Gradle per app.
- **Where it runs:** Hermes's Docker sandbox (`proxai-sandbox` image, `--network=proxai-sandbox`
  behind the egress proxy, runs as your user, 2 CPUs / 5 GB RAM). Claude Code runs there through
  the existing `claude_code` delegation plugin (`cc-run`, turn and time limits).
- **Storage:** `/workspace/proxai/apps/<app>/` in the container = `~/dev/proxai/apps/<app>/` on
  the PC. One git repo per app.

## An app folder

```
apps/tip-calc/
  proxai-app.json   {"id": "tip-calc", "name": "Tip Calc", "version": "1.0",
                     "description": "...", "features": ["vibrate"], "color": "#22AA88"}
  www/index.html    the app (plus any css/js/images it loads by relative path)
  icon.xml          optional Android vector icon (else a ProxAI spark on the app's color)
  builds.json       every build: code, version, file, SHA-256, git commit, time
  status.json       progress for the phone (planning / writing / testing / building / built / failed)
  dist/             every APK ever built: tip-calc-1.0-1.apk, tip-calc-1.1-2.apk, ...
  .git/             tags build-1, build-2, ...
```

Package name: `com.proxai.apps.<id>`. Version code goes up by one each build, so an update always
installs over the old app. **Rollback** (`proxai-app-build --rollback 1`) restores build 1's files
as a *new* build (Android won't install a lower version over a higher one).

## Phone features (`window.ProxAI`)

An app lists the features it needs in `proxai-app.json`; the APK gets only those permissions, and
calls for anything else do nothing.

| Feature | JavaScript | Android permission |
|---|---|---|
| always | `ProxAI.toast(text)`, `ProxAI.app` (id, name, version, features), saved data with `localStorage` | none |
| `vibrate` | `ProxAI.vibrate(ms)` | VIBRATE |
| `notifications` | `ProxAI.notify(title, text)`, `ProxAI.remind(date, text)` | POST_NOTIFICATIONS (asked on first use) |
| `speech` | `ProxAI.speak(text)` | none |
| `ask` | `await ProxAI.ask(prompt)` | none (goes through ProxMobile; **not connected yet**) |

Not available until you approve them case by case: internet, camera, microphone, location,
contacts, SMS, storage. The builder refuses them for now.

## Security

- Apps are written and built only inside the sandbox container; nothing generated runs on the PC.
- Generated apps have **no internet permission**, no access to the vault or your files, and only
  the permissions above. The shell serves the app's own files only (no `..`, no hidden files).
- APKs are signed with a **separate generated-apps key** (`apps/.signing/`), never ProxMobile's.
  Android still asks you before every install.
- Builder checks: id/name/version/color format, known features only, no symlinks, size limits,
  icon must be a plain vector with no references to other resources.

## Testing

```bash
# shell logic (JVM)
JAVA_HOME=~/Android/jdk17 taskset -c 0-1 ./gradlew --max-workers=2 :appshell:testDebugUnitTest
# builder, inside the real image, no network (Hermes repo)
python3 sandbox/test_app_build.py
```
On the phone: `ProxAI-Test-App-shell-test.apk` (Taildrop 10-22) has a button per feature.
It's signed with a throwaway test key, so uninstall it before installing real ProxAI apps.

## Go live (at the PC)

```bash
git -C ~/.hermes/profiles/proxai merge --no-edit worktree-proxai-apps      # includes the vault-write branch
git -C ~/dev/projects/ProxMobile merge --no-edit worktree-proxai-apps      # includes 0.3.x
docker rm -f $(docker ps -q --filter name=hermes-)                         # next tool call starts the new image
systemctl --user restart hermes-gateway-proxai proxai-phone-voice
~/dev/projects/ProxMobile/scripts/install-apps-service.sh                  # apps server + tailnet /apps
```
Then install `ProxAI-0.4.1.apk`, ask "make me a water tracker" in the app, watch the Apps tab.
The image `proxai-sandbox:2.1.302-apps` is already built on the PC; to rebuild it later:
`./gradlew :appshell:shellKit` (ProxMobile), then `sandbox/build-image.sh` (Hermes).

## Changelog

- **2026-10-22** — Decisions with Nathan: Claude Code builds and tests each app, real APKs listed
  in the Apps tab, apps kept in the container's dev workspace, one git repo per app, phone
  features, build progress; shell now, native Kotlin builds later; firmware later.
- **2026-10-22** — App shell (`appshell/`, 5 tests) · builder `proxai-app-build` (manifest,
  icon, signing, versions, git tags, rollback, progress, refusals) · sandbox image
  `proxai-sandbox:2.1.302-apps` with Java 17 + Android build-tools (not live) · sample app
  `apps-example/proxai-test` · 6 builder tests pass in the real image · test APK sent to the phone.
- **2026-10-22 (later)** — Hermes `proxai_app` tool (background builds through the Claude Code
  runner, one at a time, phone/CLI only) · Claude Code guide + example baked into the image ·
  **first real end-to-end build**: "Water Log" from a one-line request, in a container set up like
  the live one (5 turns, ~30 s, Node-tested, signed APK) · apps server (read-only, sandboxed) ·
  ProxMobile 0.4.0 Apps tab (progress, Install/Update/Open, versions, rollback, key pinning).

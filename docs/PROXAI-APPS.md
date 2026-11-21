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
| `pc-stats` (0.5.0) | `await ProxAI.pcStats()` → the PC's live GPU/CPU/RAM reading, last hour per minute, recent alerts; since 10-30 also `claude`: Claude's plan limits (session, week, per-model weeks such as Fable) and ProxAI's Claude spend (Hermes plugin proxai-claude-usage) | none (read through ProxMobile's `PcStatsProvider`, which answers only `com.proxai.apps.*` apps signed with the pinned key; the API key never leaves ProxMobile) |
| `dumpsync` (0.7.1) | `ProxAI.dumpsync.list(since)`, `.setStatus(id, status)`, `.trash()`, `.recover(id)`, `.summary(since)` → brain dumps and project notes from the vault (Hermes plugin proxai-dumpsync) | none (through `PcStatsProvider` like `pc-stats`; ProxMobile calls the dumpsync server itself with the URL + `DUMPSYNC_TOKEN` from its settings, which the app never sees; errors reject with `Error.code`: `unreachable`, `unauthorized`, `expired`, `not_found`, `blocked`, `not_declared`, …) |
| `daily` (0.7.2) | `ProxAI.daily.today()`, `.get(date)`, `.add(section, items, date?)` → the day's note (`00-Daily`) + rhythm's messages that day; add tasks / check / focus / notes through `_inbox` (proxai-dumpsync `/daily`) | none (same door and token as `dumpsync`; blank adds refused, reads never create a note). 0.7.3: `.setHabits`, `.habit`, `.habitHistory`, `.nutrition`, `.logFocus`, `.call(op, payload)` |
| `pipeline` (0.7.7) | `ProxAI.pipeline.list(lane?, closed?)`, `.item(id)`, `.due()`, `.setStage(id, stage, note?)`, `.log(id, dir, channel, text)`, `.setNext(id, action, due?)`, `.addIdea(title, summary?, lane?)`, `.setPersonal(id, bool)`, `.call(op, payload)` → the business pipeline after the scout (Hermes plugin proxai-pipeline): lanes gig / company / product / mine, stages, next steps, conversation logs; `id` is the item id or its code (G3) | none (same door and token as `dumpsync`: ProxMobile passes every op to `POST /dumpsync/pipeline/<op>` unchanged, so new pipeline ops need no ProxMobile update; errors reject with `Error.code` like `dumpsync`) |
| `docs` (0.9.8, shell kit 8) | `ProxAI.docs.status()`, `.unlock()`, `.ask(question)`, `.load()`, `.lock()` → proxDocs (Hermes plugin proxai-docs): answers about ProxAI itself with their sources; `unlock()` opens ProxAI's own proxDocs prompt (`DocsUnlockActivity`) and resolves `{unlocked}` | none (through `PcStatsProvider`; ProxMobile keeps the proxDocs session, shared with its Docs tab, so the app never sees the password or the session key; while the app shows, its shell checks in every 20 s so proxDocs stays open like on ProxAI's own screen; errors reject with `Error.code`: `locked`, `personal_mode`, `not_loaded`, `busy`, …) |
| `hub` (0.9.10) | `ProxAI.hub.state()`, `.act(name, args)`, `.capture(text)`, `.sort(id, to, project?, group?)`, `.check(id, done?)`, `.answer(id, allow, message?, stop?)`, `.call(op, payload)` → Prox Hub, Nathan's plan dashboard: what needs him (agent questions, waiting agents, BoardRoom card count), the Inbox, to-dos, the next phases, agents and usage; `act` runs any dashboard action (capture, inbox_sort, check, answer, dispatch, stop, merge, restart, kill, revive, …) | none (same door and token as `dumpsync`: `POST /dumpsync/hub/<op>`; the PC checks everything, so new hub actions need no ProxMobile update) |

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
- **Owner apps** (Prox Hub `prox-hub`, BoardRoom `boardroom`; OWNER-SIGN, ProxMobile 0.9.16) are re-signed on the
  PC, by the host after Nathan's typed yes, with his own **owner key**, never the generated-apps key. See
  [Owner apps: the owner key](#owner-apps-the-owner-key).

## Owner apps: the owner key

Every PC-built app is signed with the same generated-apps key, so "this app is called `com.proxai.apps.prox_hub`"
proves nothing: anything the builder makes could carry that name. From 0.9.16 ProxMobile hands the **Owner token**
(and with it Prox Hub, and every Pipeline op beyond the summary reads) only to a caller that is both:

1. named by Android `com.proxai.apps.prox_hub` or `com.proxai.apps.boardroom`: `ContentProvider.getCallingPackage()`
   (never the app's own request), which the framework checks against `Binder.getCallingUid()`, and which ProxMobile
   checks again against `PackageManager.getPackagesForUid(Binder.getCallingUid())` (a name that isn't one of the
   calling UID's packages is no caller and is refused like an unknown app), and alone in its Linux UID (an app with
   a shared UID, `android:sharedUserId`, is never an owner: any package in that UID could name itself as another), and
2. signed, as Android reports for the **installed** package, with the certificate whose SHA-256 Nathan pinned in
   ProxMobile → Settings → PC links → Owner apps → **Owner key fingerprint**:
   `PackageManager.hasSigningCertificate(pkg, pin, CERT_INPUT_SHA256)` must say yes **and**
   `GET_SIGNING_CERTIFICATES` must show exactly one current signer equal to the pin, no second signer and no past
   (rotated-from) certificates. ProxMobile never parses an APK file for this.

Ownership is asked of Android once per bridge call and that one answer is used by every check of the call.

No pin, no token. A pin equal to the generated-apps key is refused (it would prove nothing), and nothing can be
pinned until ProxMobile knows that key (it learns it from the first other ProxAI app installed from the Apps tab);
while it's unknown no app is an owner. The pin is shown in Settings (it isn't a secret). The only writers of the pin
and of the Owner token are ProxMobile's own Settings buttons (Pin / Forget owner key, Save / Forget Owner token); no
bridge call reads or writes either. Neither leaves the phone in a backup or a phone-to-phone transfer:
`android:allowBackup="false"` plus `res/xml/data_extraction_rules.xml`, which excludes every domain from both. A
Prox Hub or BoardRoom signed with anything else gets what every other app gets: the Pipeline's summary reads
(`list`, `due`, `clients`, `client`, `money` totals), no Prox Hub (`blocked`, nothing reaches the PC), no owner
token, no Prox Hub notices. Settings shows, for each owner app installed, whether it's signed with the pinned key.

The Apps tab installs Prox Hub and BoardRoom **only once a pin is set**, and only when the APK is signed with that
key; with no pin it refuses them ("Pin your owner key's fingerprint first"). It refuses any other app signed with
the owner key. The install session for an owner app is bound to its package (`SessionParams.setAppPackageName`),
so Android fails it if the APK is any other package. An owner app's install never becomes the generated-apps key
ProxMobile pins on its first install.

**The apps server** (`server/proxai_apps_server.py`) serves these two ids **only** from
`~/.local/share/proxai-owner-signed/<id>/<the build's dist file name>` (bound read-only in `proxai-apps.service`),
never from `dist/`, never through a symlink or anything but a regular file. A build with no host-signed copy is a
404 `not signed with the owner key on the PC yet` and isn't offered. The `sha256` and `size` the phone checks the
download against are the host-signed file's; `cert_sha256` is left empty and `owner_signed: true` is set. A dir
under `apps/` that claims one of these packages under another id, or one of these ids with another package,
serves nothing. The host-signed copies don't need an `apps-reach.json` record: the host signs them only after
Nathan's typed yes. The owner key itself must never be kept in that folder.

### Nathan's one-time steps, in order

Android won't update an app with an APK signed by a different key, so each owner app is uninstalled once and
installed fresh.

1. **On the PC, make the owner key and sign both apps.** That's the PC side (OWNER-SIGN, profile branch
   `worktree-hub-OWNER-SIGN-1116-1626`, not this repo): it makes the key, and after your typed yes signs Prox Hub
   and BoardRoom into `~/.local/share/proxai-owner-signed/prox-hub/` and `.../boardroom/`. Note the key's SHA-256
   fingerprint it prints.
2. **Reinstall the apps server unit** so it can see that folder:
   `$HOME/dev/projects/ProxMobile/scripts/install-apps-service.sh`
3. **Update ProxMobile to 0.9.16** (Apps tab: nothing changes for other apps).
4. **Pin the fingerprint**: ProxMobile → Settings → PC links → Owner apps → Owner key fingerprint, paste the
   SHA-256 from step 1 (`AB:CD:…` as keytool prints it, or plain hex), **Pin owner key**. If it says "Install one
   of your other ProxAI apps from the Apps tab first", install any other ProxAI app there, then pin.
5. **On the phone, uninstall Prox Hub and BoardRoom** (long-press → App info → Uninstall).
6. **Install the owner-signed builds** from ProxMobile's Apps tab (Install on Prox Hub, then BoardRoom). Back in
   Settings → Owner apps, the lines under the fingerprint should say both apps are "signed with your owner key".
7. **Type the Owner token** from the PC in the same section (**Save Owner token**). A token saved under 0.9.15
   stays; type it again only if the PC gave you a new one.

### What each app loses when it's uninstalled

Uninstalling deletes everything an app keeps on the phone, and the shell sets `allowBackup="false"`, so nothing
comes back from a backup. What's on the PC isn't touched: Prox Hub's inbox, questions and answers, the plan, the
Pipeline, BoardRoom's records all live on the PC and reappear on the first call.

- **Prox Hub** loses: whatever its page saved in the WebView (localStorage, IndexedDB, cookies: e.g. a tab or
  filter it remembered, an unsent draft of a capture or answer, any cached copy of the board shown offline); its
  notification permission and the settings of its two channels ("App notifications", "Questions and updates from
  your agents": sound, importance, Do Not Disturb exceptions); Prox Hub notices already in the shade; its home
  screen icon and any widget placement; anything it was sending at that moment.
- **BoardRoom** loses the same kinds of thing: its WebView storage (remembered views, drafts, cached data), its
  notification permission and channel settings, its home screen icon and widgets.
- **ProxMobile** keeps everything (tokens, the pin, its own settings). Its grant to see each app ends with the
  uninstall and comes back when ProxMobile installs it (Apps tab) or the app first calls it.

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
~/dev/projects/ProxMobile/scripts/install-thermal-watch.sh                 # 24/7 PC watch (alerts + pc-stats)
```
Then install `ProxAI-0.5.2.apk`, ask "make me a water tracker" in the app, watch the Apps tab.
The image `proxai-sandbox:2.1.302-apps` is already built on the PC; to rebuild it later:
`./gradlew :appshell:shellKit` (ProxMobile), then `sandbox/build-image.sh` (Hermes).

## Changelog

- **2026-11-16** — **OWNER-SIGN** (ProxMobile **0.9.16**, no shell kit change): the owner token goes only to Prox Hub
  or BoardRoom signed with Nathan's owner key, by Android's own word for the installed package
  (`hasSigningCertificate` with `CERT_INPUT_SHA256` plus `GET_SIGNING_CERTIFICATES`: one current signer equal to the
  pin, no past certificates, alone in its UID), against the SHA-256 he pins in Settings → Owner apps. The caller is
  `getCallingPackage()` checked against `Binder.getCallingUid()`'s packages; ownership is asked once per call. No pin,
  no token; nothing can be pinned until the shared apps key is known; a wrong signature gets the Pipeline summary
  only, and no Prox Hub. The Apps tab installs those two only once pinned and owner-signed (the session bound to
  the package), and nothing else with that key. ProxMobile's data is excluded from backups and phone-to-phone
  transfers (`data_extraction_rules.xml`). The apps server serves them only from
  `~/.local/share/proxai-owner-signed/<id>/`. See [Owner apps: the owner key](#owner-apps-the-owner-key) for his
  one-time steps and what each app loses.
- **2026-11-16** — **HUB-APP-ONLY** (ProxMobile **0.9.15**, no shell kit change): Prox Hub's presses and answers come
  only from the Prox Hub app. ProxMobile's door names the caller from Android's Binder identity (`callingPackage`,
  never from the app's request) and refuses every `hub` op, and every Prox Hub notice, for any app but
  `com.proxai.apps.prox_hub` (code `blocked`, nothing reaches the PC). Every `hub` and `pipeline` call carries
  `X-ProxAI-App: <package>`; the **owner apps** (`com.proxai.apps.prox_hub`, `com.proxai.apps.boardroom`) also send
  `X-ProxAI-Owner-Token` from the new Settings → PC links → **Owner token** (key `owner_token`, stored like the Prox
  Dump sync token; the field starts empty and never shows the saved token). Any other app gets only the Pipeline's
  summary reads (`list`, `due`, `clients`, `client`, `money` without an `id`); every other Pipeline op (writes,
  `item`, cards, mail, agents, week, plans, `client_update`) is refused on the phone. Pipeline op names may now
  contain `_`. A hub or Pipeline 403 shows the PC's own words (at most 300 characters), not Prox Dump's note-folder
  message. The dumpsync client follows no redirects, so neither token goes to a host a 3xx names. The Owner token
  field is a password field to the keyboard too (no suggestions, no autocorrect). Prox Dump sync and Daily Log calls
  are unchanged.
  **Reserved apps:** two app ids are Nathan's own and must never be built a second time or renamed into:
  **`prox-hub`** (package `com.proxai.apps.prox_hub`) and **`boardroom`** (package `com.proxai.apps.boardroom`).
  Every PC-built app is signed with the same key, so a second app with either id would install as an update over
  his. `proxai-app-build` (Hermes sandbox image, not this repo) must refuse both; the PC side (dumpsync checking
  the header and token) is not in this repo either.
  **NOTICE-QUOTE** (same build): the note of fresh PC notices that goes with Nathan's next chat message
  (`ChatNotices.note`) gives each notice's kind and title (names kept), its `decision` and `next` step when the PC
  sends them (300 characters each), then its body (caps unchanged: 1500 each, 4000 in all, brackets still turned to
  parentheses). A notice with `no_quote` gives only its kind and title (the title at most 200 characters).
  `no_quote` fails closed: absent or JSON `false` quotes; any other value (`true`, `1`, `"yes"`, `"false"` as text,
  an object, `null`) doesn't. The flag and the two fields are kept with the chat message (a restart doesn't start
  quoting it). A notification's tap or Reply carries only the notice's key: ProxMobile looks it up in its own store
  of what it fetched from the PC (`NoticeStore`, last 100) and drops a key that isn't there, so no app can put a
  made-up notice in the chat or claim a real notice's key first. The apps server (`server/proxai_apps_server.py`,
  `/v1/pc/notices`) now passes each record's `no_quote` exactly as stored (only when present), and `decision` and
  `next` only when they're text, at most 300 characters; before, only id, time, kind, title and text reached the
  phone, so every notice would have been quoted.
  **Vetted APKs only** (RESERVED-APPS round 2, same branch): the apps server lists an app as installable, marks a
  build `available` and serves its APK only when the file's own sha256 is in the proxai-apps plugin's record
  (`~/.local/state/proxai/apps-reach.json`: per app `good` = `[[code, sha]]` and `vetted_sha`). The hash is taken
  from the same open file that is then sent, cached by inode, ctime, mtime and size. "Latest" is the newest vetted
  build; anything else is a 404 with `waiting for Nathan's yes` (the app has a record, this build isn't in it) or
  `not vetted` (no record for the app). A missing or unreadable record serves nothing. `proxai-apps.service` binds
  the record read-only (reinstall the unit with `scripts/install-apps-service.sh`).
- **2026-11-15** — **PM-HUBFIX** (ProxMobile **0.9.13**, shell kit **12**): the Prox Hub app said "ProxMobile isn't
  installed or is too old" while ProxMobile was installed and unlocked, and no call reached the PC. The shell used that
  one text for every failure. Two ways get there with ProxMobile installed:
  **package visibility**: Android 11+ lets an app see ProxMobile's door only if its manifest names `com.proxai.hermes`
  in `<queries>`, ProxMobile installed it (Apps tab), or ProxMobile touched it lately (a grant that a reboot or an
  update of either app ends); otherwise `ContentResolver.call` throws "Unknown URI". And **a null answer**: Android
  returns null, with no exception, when the call dies in Binder (ProxMobile's provider threw outside its try, or its
  answer was over the ~1 MB Binder buffer; a String travels as UTF-16, so ~500K characters, e.g. `hub.save` of a
  ~370 KB file). Now the shell says which it was (codes `proxmobile_missing` = can't see ProxMobile,
  `proxmobile_too_old`, `proxmobile_no_answer`, `proxmobile_empty`, `proxmobile_error` with the exception class,
  `too_large`), and `PcStatsProvider.call` always returns "json" or "error": never null, never a Bundle with neither,
  nothing over 400K characters (800 KB as UTF-16, so a margin under Binder's ~1 MB for the Bundle and other calls
  in flight), every failure (Errors too) as an error. The template manifest has
  `<queries><package android:name="com.proxai.hermes"/></queries>`; **`proxai-app-build` must write the same line in
  each app's manifest** (Hermes sandbox image, not in this repo).
  Same branch, the Live/Offline flicker after each Allow: ProxMobile's lock refusal ("ProxAI is locked…", which
  never reaches the PC) came out with code `unreachable`; it is `locked` now (`busy` while ProxMobile starts). And
  Prox Hub calls from one app now go through ProxMobile one at a time, in order (`ShellLogic.CallGate`): Binder's
  ~1 MB answer buffer is shared by all of an app's calls in flight, so state + full at once could overflow it.

- **2026-11-08** — **Prox Hub bridge** (ProxMobile **0.9.10**, shell kit v9): the `hub` feature gives PC-built apps
  Prox Hub (the plan dashboard): state in one call, every dashboard action through `act`, quick capture into the Inbox.
  Same door and token as Prox Dump sync (`POST /dumpsync/hub/<op>`).

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
- **2026-10-22 (night)** — **PC tracking** (Nathan: "a system tracking app, with background
  alerts"): the 10-22 temporary thermal watch became a 24/7 service (`server/proxai_thermal_watch.py`,
  daily CSVs, `latest.json`, numbered `alerts.jsonl`, Ollama unload through its API); the apps
  server gained `/v1/pc/stats` and `/v1/pc/alerts`; ProxMobile **0.5.0** checks for PC alerts about
  every 15 min (Android's minimum while closed; nothing is lost, alerts are numbered) and right away
  when opened, and passes stats to apps with the new **`pc-stats`** feature; shell kit v2; the
  Claude Code guide documents it, so "make me a PC monitor app" works without extra setup.
- **2026-10-26** — **Prox Dump sync for apps** (`dumpsync` feature, ProxMobile **0.7.1**, shell kit v4):
  the same door as `scout` (shell bridge → `PcStatsProvider`, package + signing key + declared
  feature checked) but ProxMobile calls the proxai-dumpsync server directly (`<server>/dumpsync`
  or the URL in Settings → Prox Dump sync, bearer `DUMPSYNC_TOKEN`), because the apps server has no
  network. Structured errors (`Error.code`, `Error.status`). Builder accepts `dumpsync` with no
  permissions; the guide and `proxai-app-check` (fake vault, trash, 24 h window) know it.
  Proven in an Android 15 emulator against a copy of the vault: list (52 notes), setStatus,
  trash, recover, summary, plus `expired`, `not_found`, `blocked`, `unauthorized`,
  `unreachable` and `not_declared`.
- **2026-10-26** — **Daily Log for apps** (`daily` feature, ProxMobile **0.7.2**, shell kit v5): the
  `dumpsync` door and token, new routes `GET/POST /daily` in proxai-dumpsync (`daily.py`). Reads the
  day's note (never creates it), `_inbox` daily notes still pending, and rhythm's messages from the
  notice feed; adds one `type: daily` note to `_inbox` (tasks, check, focus, notes; today or yesterday)
  that the refiler merges. Blank adds are refused so the vault gets no empty daily notes. The guide
  tells apps to show rhythm's messages instead of sending their own notifications.
- **2026-10-27** — **Daily Log v2** (ProxMobile **0.7.3**, shell kit v6): habits (list with `by` times and
  count targets, check-ins, history with streaks), the Nutrition app's meals per day, and focus sessions,
  all on the same `daily` feature. ProxMobile now passes any other daily op to `POST /daily/<op>` and lets
  the PC check it, so later daily features need only the PC side. Habits and nutrition are copied into
  the note's 💊 Habits / 🥗 Nutrition sections; Brain dump left the daily template.
- **2026-10-28** — **Pipeline bridge** (ProxMobile **0.7.7**, shell kit v7): the `pipeline` feature gives
  PC-built apps the business pipeline after the scout (proxai-pipeline: gigs, companies, paid apps, own
  ideas; stages, next steps, conversation logs). Same door and token as `dumpsync`; every op goes to
  `POST /dumpsync/pipeline/<op>` unchanged and the PC checks it, so decision cards and later ops need only
  the PC side. The Pipeline app itself is built with the app builder.
- **2026-10-30** — **Claude usage for Vitals** (P2, no new ProxMobile build): `/v1/pc/stats` gained a
  `claude` block, so any app with `pc-stats` gets it. It holds Claude's plan limits (the same bars as
  Claude Code's `/usage`: session, week, Fable week) and what Hermes spent on Claude models. The Hermes
  plugin proxai-claude-usage writes it every 10 minutes, and the server only reads it
  (`~/.local/state/proxai/claude-usage`, bound read-only in `proxai-apps.service`). Vitals itself is
  updated with the app builder.
- **2026-10-31** — **App research** (P3, ProxMobile 0.9.5). Nathan: REA is for other apps, widgets and
  firmware, not his own apps. In Chat, Attach > **An app on this phone** lists the other apps (launcher apps
  plus apps that only offer widgets; ProxAI and every `com.proxai.*` app are left out) with their widget
  counts. It asks `GET /v1/research/<package>/<version code>?bytes=N` what's already there and whether N bytes
  fit, then sends each missing APK file with `PUT /v1/research/<package>/<version code>/<file>` (the main APK
  always as `base.apk`, also for preinstalled apps that keep their own name, plus the splits). They land in
  `~/dev/proxai/app-research/<package>-<code>/` with an `app.json` (label, version, size and SHA-256 per file);
  the note names the folder and the app's widgets (label + provider class). Attach > **A file to look inside
  (firmware…)** sends any file with `PUT /v1/research/files/<name>` (after `GET /v1/research/files?bytes=N`) to
  `app-research/files/<time>-<name>`, never overwritten. Limits: 1 GiB a file, 20 GB in all, never past 5 GB
  free; an APK that isn't a ZIP is refused, and a cut-off upload leaves nothing. The server never creates or
  deletes that folder (go-live: `mkdir`); it's bound writable in `proxai-apps.service`. Hermes then runs
  `claude_code` with `reverse_engineering` (REA in the sandbox: APK decompiling with JADX, firmware unpacking
  with Unblob) in project `app-research`, where the sandbox sees the files.
- **2026-11-01** — **proxDocs** (P4, ProxMobile 0.9.7). The new **proxDocs** tab asks the Hermes plugin
  proxai-docs about ProxAI itself (how each plugin, app and tool works, what changed when). The server forwards
  `GET /v1/docs/status` and `POST /v1/docs/unlock|alive|lock|load|ask` as is to the plugin's Unix socket
  `~/.local/state/proxai/docs/api/docs.sock` (bound read-only in `proxai-apps.service`, like the Study Coach's),
  with a 300 s wait for answers. Bodies are at most 16 KB and never logged: they carry the proxDocs password and
  the questions. The plugin answers only in server mode with the local model loaded, behind its own password
  (a hash in the vault's 07-Security); an unlock lasts as long as the app lock does.
- **2026-11-01** — **proxDocs for apps** (`docs` feature, ProxMobile 0.9.8, shell kit 8). Nathan: the app builder
  builds the apps; the platform gives them a door. `ProxAI.docs.*` goes through `PcStatsProvider` (method `docs`,
  feature `docs`) to ProxMobile's proxDocs session, which then calls the apps server's `/v1/docs`. The password is
  only ever typed into ProxAI's own prompt: `ProxAI.docs.unlock()` starts ProxMobile's exported
  `DocsUnlockActivity` for a result, which opens only for a `com.proxai.apps.*` app signed with the pinned key that
  declared `docs` (the provider's own check), shows the app lock first if ProxAI is locked, then the proxDocs
  password. proxDocs stays open while ProxAI or a proxDocs app is in use (the app's shell checks in every 20 s with
  a quiet `alive` that never raises the unlock notification) and locks after the lock time with neither; "Lock now"
  locks it too.

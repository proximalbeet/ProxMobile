package com.proxai.hermes.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** What changed in each version, shown in the app so updates can be checked without GitHub. */
object Changelog {
    data class Release(val version: String, val date: String, val items: List<String>)

    val releases = listOf(
        Release("0.9.19", "2026-11-18", listOf(
            "Notices from your PC that it marks \"thread\" now go to the chat only: no notification and no buzz," +
                " nothing on the lock screen, whatever kind they are.",
            "Hey Prox: say it and the voice overlay opens (Settings > Voice, off until you turn it on; mute from the Hey Prox tile). The word is heard on the phone only; on the lock screen it follows your app lock, and an OK on your PC always needs your tap.",
        )),
        Release("0.9.18", "2026-11-17", listOf(
            "Vault changes: every change to your notes you approved (from chat, a call or the terminal) comes as a " +
                "notification, \"📝 <note> <how> (from your chat)\", with an Undo button when it can be undone. " +
                "Settings > PC links lists last week's. If the note changed since, or undo isn't set up on the PC, " +
                "it says so. Needs the Prox Dump sync and Owner tokens.",
        )),
        Release("0.9.17", "2026-11-17", listOf(
            "Prox's voice: the PC's voice server now only passes your words to ProxAI and plays back what ProxAI " +
                "says (mp3); it holds no key, no model and no network of its own. Apps' voice result says \"pc\" " +
                "where it said \"kanye\".",
        )),
        Release("0.9.16", "2026-11-16", listOf(
            "Settings > PC links > Owner apps: pin your owner key's fingerprint. The Owner token, Prox Hub and the " +
                "Pipeline's changes now go only to Prox Hub and BoardRoom when Android says they're signed with that " +
                "key; no fingerprint, no token. Signed with anything else, they get what other apps get",
            "The Apps tab installs Prox Hub and BoardRoom only after you pin your owner key, and only when they're " +
                "signed with it; nothing else signed with it installs",
            "ProxMobile's settings and tokens are never copied to a backup or a new phone",
        )),
        Release("0.9.15", "2026-11-16", listOf(
            "Prox Hub answers only the Prox Hub app; Pipeline changes only Prox Hub and BoardRoom (other apps read " +
                "summaries). New Settings > PC links > Owner token",
        )),
        Release("0.9.14", "2026-11-16", listOf(
            "Chat runs on Sonnet 5.5 by default (Settings > Model > Cloud model; Haiku, Opus and Fable are still there)",
            "Settings > Model is now Cloud or Local. \"Auto\" is gone: it sent chat to the PC's local model in server " +
                "mode; a phone that had Auto now uses the cloud model",
            "The app only ever asks for a model on its list: the local model is picked with buttons, not typed",
            "The first word of a reply shows as soon as it arrives: the app no longer checks the PC's mode before sending " +
                "(that could hold a message up to 5 seconds)",
        )),
        Release("0.9.13", "2026-11-15", listOf(
            "PC-built apps (Prox Hub) no longer say \"ProxMobile isn't installed\" for every problem: a failed call now " +
                "says what failed (ProxAI locked, an answer too large, ProxMobile hidden from the app), and an answer " +
                "too big for Android (over 400K characters) comes back as an error instead of nothing (shell kit 12)",
        )),
        Release("0.9.12", "2026-11-08", listOf(
            "Prox Hub apps can save the files your agents make (contracts, templates, reports) to Downloads/Prox Hub " +
                "(shell kit 11)",
        )),
        Release("0.9.11", "2026-11-08", listOf(
            "Prox Hub's notifications (agent questions, merges, the kill switch) now come from the Prox Hub app itself; " +
                "ProxMobile only carries them. Until that app is installed they still show here",
        )),
        Release("0.9.10", "2026-11-08", listOf(
            "PC-built apps can open Prox Hub (\"hub\" feature): your plan, to-dos, inbox for quick thoughts, agents and " +
                "the questions they're asking you, with the same actions as the dashboard",
        )),
        Release("0.9.9", "2026-11-02", listOf(
            "PC notices arrive within seconds: calendar heads-ups, app builds, scheduled jobs and the brief come over " +
                "one quiet connection to your PC instead of Android's check every 15 minutes (the \"Connected to your " +
                "PC\" notification; switch: Settings > Notifications > Live PC notices)",
            "When ProxAI needs your OK (an app build or delete, a mode switch, a scheduled job, a vault write, a GitHub " +
                "change), a pop-up asks with Reject and Accept, in Chat and in the power-button assistant. No more " +
                "typing yes, or saying it twice",
            "Your message is only your message: after a compaction the summary rides along separately, so ProxAI (and " +
                "the PC) no longer take old open items or times for something you just asked. Reminders are listed only " +
                "when you talk about them",
            "Scheduled app builds work again: the build gets the words you scheduled, and the notice no longer says it " +
                "couldn't start while it was building",
            "Settings is a short list of pages now: Connection, Chat and models, Voice and assistant, Notifications, " +
                "Security, PC links, each saying how it's set",
            "ProxAI line: with \"No PIN while unlocked\" on (Settings > Security), calling the ProxAI number while " +
                "ProxAI is unlocked skips the PIN, and ProxAI stays unlocked until the call ends, then relocks on its timer",
        )),
        Release("0.9.8", "2026-11-01", listOf(
            "Apps can use proxDocs now: an app you build with the \"docs\" feature asks it questions. To unlock, the app " +
                "opens ProxAI's own proxDocs prompt, so the app never sees your password",
            "proxDocs stays open while you use such an app, and locks after your lock time with neither the app nor " +
                "ProxAI in use. Lock now in Settings locks it too",
        )),
        Release("0.9.7", "2026-11-01", listOf(
            "proxDocs, the new Docs tab: ask about ProxAI itself, like how a plugin, app or tool works or what changed " +
                "when. The local model on your PC answers from ProxAI's docs, code docs, git history and your apps' plans, " +
                "and shows its sources",
            "It has its own password (set it once on the PC: proxdocs hash > ~/Documents/ProxVault/07-Security/" +
                "proxdocs-password.hash), stays open until ProxAI's app lock locks again, and only works in server mode " +
                "while the local model is loaded. What you ask stays on that screen: never in Chat or ProxAI's memory",
        )),
        Release("0.9.6", "2026-11-01", listOf(
            "Fixed: sending an app or a file to the PC stopped when you left ProxAI, it locked or the phone turned " +
                "(your first try, the Claude app, never arrived). It now keeps going in the background with a " +
                "progress notification, and the app joins your next message when it's on the PC",
            "While it sends, the chat shows its progress above the message box; tap ✕ there to stop it",
        )),
        Release("0.9.5", "2026-10-31", listOf(
            "Look inside other apps, widgets and firmware: in Chat, tap Attach > An app on this phone (apps with " +
                "home-screen widgets say so) or A file to look inside (a firmware image, an APK file). It goes to your PC; " +
                "then ask what it's built with or how it works",
            "On the PC, Claude Code looks inside it with REA, its reverse-engineering tools, in the sandbox. Nothing is " +
                "installed or run, and an app the PC already has isn't sent again. Your own ProxAI apps aren't listed: " +
                "their source is on the PC",
        )),
        Release("0.9.4", "2026-10-31", listOf(
            "Workbench logins pop up right away: ProxAI keeps a quiet connection to the Workbench, so a login you ask " +
                "for on your PC buzzes here within a second or two. Tap it and pick the number that browser shows",
            "While it listens, Android shows a small \"Ready for Workbench logins\" notification you can hide. Turn " +
                "listening off in Settings > Workbench logins; for instant pop-ups, let ProxAI run in the background there",
        )),
        Release("0.9.3", "2026-10-31", listOf(
            "Workbench logins: when a browser asks to log in to the Workbench (the AI-engineering IDE on your PC), " +
                "ProxAI shows three numbers while it's open and unlocked. Tap the one that browser shows, or Not me",
            "Pair once: on the PC run python3 workbench.py phone-key and scan the code. Apps never see this key",
        )),
        Release("0.9.2", "2026-10-30", listOf(
            "Fixed: the power-button assistant didn't open while the phone was unlocked (0.9.1). It now waits up to " +
                "0.7 s for the screen picture, then opens the way it always did",
            "With the app lock on, the assistant no longer closes itself while the fingerprint prompt is up",
        )),
        Release("0.9.1", "2026-10-30", listOf(
            "\"Look at my screen\" in the power-button assistant: ProxAI looks at what's behind it, only when you tap. " +
                "Turn on \"Use screenshot\" in Android's Settings > Apps > Default apps > Digital assistant app",
            "The screen is kept only for that one press and goes to ProxAI only if you tap the button",
            "Apps tab: \"Delete from PC\" deletes right away (once the PC is updated); no second yes in Chat",
        )),
        Release("0.9.0", "2026-10-30", listOf(
            "App lock (Settings > App lock): fingerprint or face, with an app PIN as backup. It locks again once " +
                "ProxAI has been out of sight for 1 minute (or 30 s, 5 or 15 min)",
            "While locked, nothing reaches the AI or your data: the app, the power-button assistant, replies typed into " +
                "notifications and your PC-built apps all wait for the unlock. A reply typed while locked goes out " +
                "right after you unlock",
            "Apps you're using keep working; leave them for the lock time and they ask you to unlock ProxAI first",
            "With the lock on, the app switcher doesn't show your chat. \"Lock now\" is in Settings",
        )),
        Release("0.8.6", "2026-10-30", listOf(
            "A switch for each kind of notification (Settings > Notifications): reminders, calendar heads-ups, " +
                "brief and nudges, app builds and PC updates, replies, PC temperature alerts. Switched off means no " +
                "buzz, but it still lands in Chat. PC temperature alerts stay off (Monitor and Vitals show them)",
            "Calendar heads-ups have their own notification channel and no longer crowd Chat; tap one to talk about it",
            "Trash: \"Delete for good\" asks first, naming the chat. Restore asks too when it would move the chat " +
                "that's there now to the Trash",
        )),
        Release("0.8.5", "2026-10-29", listOf(
            "Fixed: the chat header shows the cost again. The Channels button (0.8.1) left less room, so the size-and-cost " +
                "line wrapped and the bar cut off the half with the dollar amount. It's now one short line with the cost " +
                "first, like \"\$0.005 so far · ~33k tokens\"",
            "Long channel names stay on one line in the header",
        )),
        Release("0.8.4", "2026-10-29", listOf(
            "Fixed: a long message got cut off when the keyboard opened. The chat lost about 140 dp to space counted " +
                "twice (the tab bar and the status bar); now the message box sits right on the keyboard",
            "Fixed: in the power-button assistant the text box was squeezed (its first line cut in half) and the Talk " +
                "button pushed off screen when the replies filled up; the replies now make room instead",
        )),
        Release("0.8.3", "2026-10-29", listOf(
            "Links in replies are tappable, and replies that used the web list their Sources, each labelled " +
                "✅ Human-verified (a site you trust), 🤖 AI-checked (ProxAI confirmed it in a second source) or ❔ Unknown",
            "Tap a source to open it or to trust its site. Your trusted sites are in Settings > Context and cost",
        )),
        Release("0.8.2", "2026-10-29", listOf(
            "Attach button (📎 next to the message box): send photos, PDFs and text files (notes, code, CSV...). " +
                "Photos are shrunk and turned upright; a PDF's first 5 pages go as pictures; text files go as text. " +
                "Up to 8 pictures per message",
            "A message with a picture goes to the cloud model, even in server mode (the local model may not read pictures)",
            "ProxAI can send pictures and PDFs into the chat (a chart, a study sheet...): pictures show in the message " +
                "(tap for full screen), PDFs open in your PDF viewer. They stay on the phone once loaded",
        )),
        Release("0.8.1", "2026-10-29", listOf(
            "Channels: the # button in the chat header lists the ProxAI DM and your channels. A channel is a separate " +
                "chat for a project or topic (Homework, One Touch...) with its own memory on the PC and its own model " +
                "(Haiku, Sonnet, Opus, Fable, the local model, or the same as Settings). Briefs and notices stay in the DM",
            "Replies from a channel say which channel they're from, and Reply in the notification answers there. " +
                "The power-button assistant always talks in the ProxAI DM",
            "Trash: \"New chat\" and deleting a channel no longer wipe anything. The conversation goes to the trash " +
                "(# button > Trash); Restore brings it back with its memory. It empties itself after 30 days (Settings)",
            "Fixed: stopping a reply right as you started a new chat could leave the chat stuck on \"working\"",
        )),
        Release("0.8.0", "2026-10-29", listOf(
            "Chat is ProxAI's DM: the morning brief, nudges, end of day, scout ideas, pipeline and roadmap news " +
                "now arrive as messages in the chat (within a minute while it's open), so you can answer them right there",
            "Tapping any PC notification (app ready, wiki, Study Coach...) puts its whole text in the chat",
            "Reply straight from the notification: briefs, nudges, scout ideas and \"ProxAI replied\" have a Reply " +
                "button. Short answers like \"done 1 3\" or \"drop 2\" work from there too",
            "Your next message carries the notices that came in since your last one, so ProxAI knows what you're answering",
            "Every message shows its time, with Today / Yesterday dividers",
        )),
        Release("0.7.7", "2026-10-28", listOf(
            "PC-built apps can show and update your business pipeline (\"pipeline\" feature): gigs, companies, " +
                "paid apps and your own ideas, with stages, next steps and a log of each conversation",
            "New pipeline features on the PC (like decision cards) won't need a ProxMobile update",
        )),
        Release("0.7.6", "2026-10-28", listOf(
            "Settings > Model: pick the cloud model with buttons (Haiku 5.5, Sonnet 5.5, Opus 5.5, Fable 5.1). Haiku is the " +
                "default, and the pick is now actually used (a typed model name used to be ignored)",
            "The chat header shows this session's real cost from the PC (prompt caching included). The old " +
                "number was an uncached upper bound that could read 10-30x too high",
        )),
        Release("0.7.5", "2026-10-28", listOf(
            "Settings: text is readable everywhere, and the Model section only shows what your mode uses " +
                "(Cloud: cloud model; Local: local model; Auto: both)",
            "ProxAI can move or cancel a reminder it set (\"move my 5pm reminder to 6\", \"cancel the oven reminder\")",
            "Compact summaries are short sections (where we are, done, keep in mind, still open) with bullets",
            "PC temperature alerts are off by default (the Monitor app covers them); turn them on in Settings",
        )),
        Release("0.7.4", "2026-10-27", listOf(
            "Install and Update work on the first tap: the button waits for Android to finish, then shows the new version",
            "When a build needs a decision from you, its card says \"Needs your answer\" with an Answer button " +
                "instead of failing or quietly redoing the work",
        )),
        Release("0.7.3", "2026-10-27", listOf(
            "Daily apps can track habits (time goals, counts, streaks), send the Nutrition app's meals to your " +
                "daily note, and log focus sessions",
            "New daily features on the PC no longer need a ProxMobile update",
        )),
        Release("0.7.2", "2026-10-26", listOf(
            "PC-built apps can show your daily note and the daily rhythm's messages (brief, nudges, end of day), " +
                "and add tasks, ticks, focus and notes to it (\"daily\" feature). Blank entries are refused, so no empty notes",
            "Uses the Prox Dump sync token you already set; apps never see it",
        )),
        Release("0.7.1", "2026-10-26", listOf(
            "PC-built apps can read and sort your brain dumps and project notes (\"dumpsync\" feature): list, " +
                "mark reviewed or done, trash and recover within 24 hours",
            "Settings → Prox Dump sync: the token from the PC (and an optional URL); apps never see either",
        )),
        Release("0.7.0", "2026-10-25", listOf(
            "PC-built apps run full screen: no title bar. A small round ⋯ button in the top-right corner has " +
                "Reload and Stop talking (quick apps made in chat keep their title bar)",
            "Apps can talk in the Kanye voice (\"voice\" feature); the phone voice takes over if the PC can't be reached",
            "Apps built on your PC can now ask ProxAI questions (\"ask\"), listen to spoken answers (\"listen\": " +
                "the app only gets the words) and open files you pick (\"files\"). Groundwork for the Study Coach",
            "Study Coach support: a PC-built app with the \"tutor\" feature can run your study notebooks (sprints, " +
                "hints, mastery levels, cited answers, audio overview, voice quiz). Prompt ProxAI to build the app",
            "Rebuild older PC-built apps (Remix) to get the full-screen look",
        )),
        Release("0.6.4", "2026-10-24", listOf(
            "Gig board for apps you build: a ProxAI app with the \"scout\" feature can show the overnight scout's " +
                "gigs and app ideas, move them (interested, pitched, won, done, passed), keep notes and hours, and " +
                "block time on your ProxAI calendar",
        )),
        Release("0.6.3", "2026-10-23", listOf(
            "Scheduled jobs: \"every morning at 8 summarize my calendar\", \"delete the old app at 2am\". ProxAI asks " +
                "first; your yes also approves the action. Say \"what's scheduled\" or \"cancel the … job\"",
            "Notices from the PC as notifications: scheduled-job results, \"your app is ready\", wiki updates " +
                "(Settings → Notifications)",
        )),
        Release("0.6.2", "2026-10-23", listOf(
            "Apps built on your PC: ProxAI asks before it builds, changes or rolls back an app; only your yes " +
                "starts it",
            "A \"Stop\" button on an app that's building (also \"stop the build\" in chat)",
        )),
        Release("0.6.1", "2026-10-23", listOf(
            "Much faster replies from the PC's local model: the app's instructions no longer change every " +
                "minute (the clock moved into your message), so the PC reuses its cache instead of re-reading " +
                "the whole chat before every answer (was 40-100 s)",
        )),
        Release("0.6.0", "2026-10-23", listOf(
            "Voice replies come in short parts: each part is its own bubble and is spoken as soon as it " +
                "arrives, while ProxAI writes the next one",
            "Token counter shows the real conversation size from the PC (\"33k context\"), not every step of " +
                "a turn added up (it showed 126k / 376k when each call was ~32k)",
            "Reminders: no more alarms for times already past or repeats of one already set; ProxAI can set " +
                "one on its own per reply, only for something you mentioned",
            "\"How do I…\" questions get a short explanation instead of ProxAI going and doing it",
        )),
        Release("0.5.2", "2026-10-23", listOf(
            "Apps tab: \"Remove…\" on apps built on your PC: uninstall from the phone, and/or delete from " +
                "the PC (ProxAI asks you to confirm; the files go to a trash folder on the PC)",
        )),
        Release("0.5.1", "2026-10-22", listOf(
            "Apps tab: the \"Built on your PC\" and \"On this phone\" headings were dark on dark; now readable",
        )),
        Release("0.5.0", "2026-10-22", listOf(
            "PC temperature alerts: the PC watches GPU/CPU/RAM every second (and unloads the local " +
                "model if the GPU gets critical); the phone shows its alerts (checked about every 15 min " +
                "while ProxAI is closed, right away when you open it). Settings → Notifications",
            "Apps ProxAI builds can show your PC's live stats (new \"pc-stats\" feature), e.g. \"make me " +
                "a PC monitor app\". Only apps signed with your ProxAI apps key can read them",
        )),
        Release("0.4.1", "2026-10-22", listOf(
            "Auto-compact measures correctly; the limits didn't change (cloud 60k, PC model 85% ≈ 56k)",
            "Server mode: the PC compacts by itself, and the app now shows its \"Compacting the " +
                "conversation on the PC…\" note. The app no longer guesses there (the local model only " +
                "reports newly read tokens)",
            "Cloud: replies that used tools no longer trigger it early (their token count covered every " +
                "step of the reply); the header shows the estimated size (~)",
        )),
        Release("0.4.0", "2026-10-22", listOf(
            "Apps tab → \"Built on your PC\": ask ProxAI for an app and Claude Code builds a real app " +
                "(its own icon and APK) on the PC; progress shows here while it builds",
            "Install / Update / Open buttons; Android asks before every install. The first install " +
                "remembers the ProxAI apps signing key and refuses APKs signed with any other",
            "Versions: every build is kept; \"Roll back\" asks ProxAI to rebuild an older version as an update",
            "\"Change with ProxAI\" sends a change request for that app",
            "Needs the PC side: Hermes proxai_app tool + apps server (docs/PROXAI-APPS.md)",
        )),
        Release("0.3.2", "2026-10-22", listOf(
            "Kanye voice no longer gives up after one error: a failed sentence is retried once, " +
                "and if it still fails only that sentence uses the phone voice",
            "Shorter voice pieces (180 characters, was 300) so the PC's graphics card doesn't run " +
                "out of memory while the local model is loaded (the \"CUDA error\")",
            "PC side (after restarting the voice service): out of GPU memory → clears memory and " +
                "retries the piece in two halves",
        )),
        Release("0.3.1", "2026-10-22", listOf(
            "New look: glowing blue gradients instead of purple",
            "Named ProxAI everywhere (was Hermes), including the \"Ask ProxAI\" tile",
            "New ProxAI logo: a \"P\" with a spark (app icon and in the app)",
            "Shows \"thinking…\" while the local model thinks, instead of a frozen blank line",
            "Shows \"still working…\" if nothing arrives for 20 seconds",
            "Hands-free: no error when you answer after a reply (waits for the mic, retries if busy)",
            "Hands-free: saying nothing after a reply ends quietly, no error",
            "This What's new list (Settings → What's new)",
        )),
        Release("0.3.0", "2026-10-22", listOf(
            "Voice no longer sends half a sentence: keeps listening through pauses, sends after 3 s of quiet",
            "Send button while you talk; pause length in Settings → Speech input",
            "Speech-to-text resets itself after errors (no more phone restarts)",
            "Fewer misheard words: word list + \"heard => meant\" fixes",
            "Speech log in Settings → Speech input",
            "Talking while ProxAI is busy queues your message instead of losing it",
            "Server mode on the PC → the phone uses the local qwen3.5:35b-a3b model",
            "Settings → Model: Auto / Cloud / Local",
            "Reminders: \"remind me in 20 minutes to…\" → phone notification (Snooze / Done)",
            "Notification when a reply finishes while the app is closed",
            "Each reply shows the model and tokens; header shows context size and cost",
            "Auto-compact long chats (summary + fresh session) and a Compact button",
        )),
    )

    val latest get() = releases.first()

    private const val PREF = "whats_new_seen"

    /** True once per new version (then remembered). */
    fun shouldShow(context: Context): Boolean {
        val prefs = context.getSharedPreferences("proxai", Context.MODE_PRIVATE)
        if (prefs.getString(PREF, null) == latest.version) return false
        prefs.edit().putString(PREF, latest.version).apply()
        return true
    }
}

@Composable
fun ReleaseNotes(releases: List<Changelog.Release>) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        releases.forEach { r ->
            Text("Version ${r.version} · ${r.date}", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
            r.items.forEach { item ->
                Row {
                    Text("✓", color = MaterialTheme.colorScheme.secondary, fontWeight = FontWeight.Bold)
                    androidx.compose.foundation.layout.Spacer(Modifier.width(8.dp))
                    Text(item, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

/** Top of Settings: installed version + the What's new list. */
@Composable
fun AboutCard() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var open by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    val installed = androidx.compose.runtime.remember {
        runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "?"
    }
    androidx.compose.material3.Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp)) {
        Row(Modifier.padding(14.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            ProxLogo(40.dp)
            androidx.compose.foundation.layout.Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                BrandTitle("ProxAI")
                Text("Version $installed", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = { open = true }) { Text("What's new") }
        }
    }
    if (open) WhatsNewDialog { open = false }
}

@Composable
fun WhatsNewDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("What's new in ProxAI ${Changelog.latest.version}") },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()).padding(end = 4.dp)) {
                ReleaseNotes(Changelog.releases)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Got it") } },
    )
}

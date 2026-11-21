package com.proxai.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShellLogicTest {
    @Test fun infoKeepsOnlyKnownFeatures() {
        val i = ShellLogic.parseInfo("""{"id":"tip-calc","name":"Tip Calc","version":"1.2",
            "features":["vibrate","internet","camera","notifications"],"color":"#22aa88"}""")
        assertEquals("tip-calc", i.id)
        assertEquals(setOf("vibrate", "notifications"), i.features)
        assertEquals(setOf("pc-stats"), ShellLogic.parseInfo("""{"features":["pc-stats","gpu"]}""").features)
        assertEquals(0xFF22AA88.toInt(), i.color)
    }

    @Test fun scoutFeatureAndApi() {
        assertEquals(setOf("scout"), ShellLogic.parseInfo("""{"features":["scout"]}""").features)
        assertTrue(ShellLogic.SHIM.contains("scout: function"))
        assertTrue(ShellLogic.SHIM.contains("n.scoutUpdate(id, JSON.stringify(change || {}))"))
    }

    @Test fun dumpsyncFeatureAndApi() {
        assertEquals(setOf("dumpsync"), ShellLogic.parseInfo("""{"features":["dumpsync","internet"]}""").features)
        for (api in listOf("window.ProxAI.dumpsync = {", "list: function(since){ return dcall({op: 'list'",
                "setStatus: function(id, status){ return dcall({op: 'setStatus'", "trash: function(){ return dcall({op: 'trash'})",
                "recover: function(id){ return dcall({op: 'recover'", "summary: function(since){ return dcall({op: 'summary'",
                "n.dumpsync(id, JSON.stringify(o))", "e.code = r.code || 'error'")) assertTrue(api, ShellLogic.SHIM.contains(api))
        // The shim never sees a URL or token: only {"op", ...} goes out.
        assertTrue(!ShellLogic.SHIM.contains("dumpsync_token") && !ShellLogic.SHIM.contains("Bearer"))
    }

    @Test fun dumpsyncErrorsBeforeThePc() {
        fun code(msg: String) = org.json.JSONObject(ShellLogic.dumpsyncError(msg)).getString("code")
        assertEquals("not_declared", code("This app wasn't built with the 'dumpsync' feature."))
        assertEquals("proxmobile_missing", code("ProxMobile isn't installed or is too old (needs 0.7.0)"))
        assertEquals("not_trusted", code("This app isn't signed with the ProxAI apps key"))
        assertEquals("not_trusted", code("Install a ProxAI app from the Apps tab first"))
        assertEquals("proxmobile_too_old", code("unknown method"))
        assertEquals("bad_request", code("That request is too big"))
        val o = org.json.JSONObject(ShellLogic.dumpsyncError("This app wasn't built with the 'dumpsync' feature."))
        assertEquals(false, o.getBoolean("ok"))
        assertEquals("This app wasn't built with the 'dumpsync' feature.", o.getString("error"))
    }

    @Test fun dailyFeatureAndApi() {
        assertEquals(setOf("daily"), ShellLogic.parseInfo("""{"features":["daily","internet"]}""").features)
        for (api in listOf("window.ProxAI.daily = {", "today: function(){ return lcall({op: 'get'})",
                "get: function(date){ return lcall({op: 'get', date: day(date)})", "add: function(section, items, date){ return lcall({op: 'add'",
                "n.daily(id, JSON.stringify(o))", "setHabits: function(list){ return lcall({op: 'habits'",
                "habit: function(id, action, n, date){ return lcall({op: 'habit'", "habitHistory: function(days){ return lcall({op: 'habit-history'",
                "nutrition: function(meals, targets, date){ return lcall({op: 'nutrition'", "logFocus: function(s){",
                "start: iso(s.start), end: iso(s.end)", "o.op = String(op); return lcall(o);")) assertTrue(api, ShellLogic.SHIM.contains(api))
    }

    @Test fun dailyErrorsBeforeThePc() {
        fun o(msg: String) = org.json.JSONObject(ShellLogic.dailyError(msg))
        assertEquals("not_declared", o("This app wasn't built with the 'daily' feature.").getString("code"))
        assertEquals("not_trusted", o("This app isn't signed with the ProxAI apps key").getString("code"))
        assertEquals("proxmobile_too_old", o("unknown method").getString("code"))
        assertEquals("ProxMobile is too old for Daily Log (needs 0.7.2)", o("unknown method").getString("error"))
        assertEquals("ProxMobile is too old for Prox Dump sync (needs 0.7.1)",
            org.json.JSONObject(ShellLogic.dumpsyncError("unknown method")).getString("error"))
    }

    @Test fun pipelineFeatureApiAndErrors() {
        assertEquals(setOf("pipeline"), ShellLogic.parseInfo("""{"features":["pipeline","internet"]}""").features)
        for (api in listOf("window.ProxAI.pipeline = {", "n.pipeline(id, JSON.stringify(o))",
                "list: function(lane, closed){ return pcall({op: 'list'", "item: function(id){ return pcall({op: 'item'",
                "due: function(){ return pcall({op: 'due'})", "setStage: function(id, stage, note){ return pcall({op: 'stage'",
                "log: function(id, dir, channel, text){ return pcall({op: 'log'", "setNext: function(id, action, due){",
                "addIdea: function(title, summary, lane){ return pcall({op: 'idea'",
                "setPersonal: function(id, personal){ return pcall({op: 'personal'", "o.op = String(op); return pcall(o);"))
            assertTrue(api, ShellLogic.SHIM.contains(api))
        fun o(msg: String) = org.json.JSONObject(ShellLogic.pipelineError(msg))
        assertEquals("not_declared", o("This app wasn't built with the 'pipeline' feature.").getString("code"))
        assertEquals("ProxMobile is too old for the Pipeline (needs 0.7.7)", o("unknown method").getString("error"))
        assertEquals("bad_request", o("That request is too big").getString("code"))
    }

    @Test fun hubNoticesOnlyFromProxMobileIntoHubApps() {
        assertTrue(ShellLogic.acceptHubNotice("com.proxai.hermes", setOf("hub", "notifications")))
        assertTrue(ShellLogic.acceptHubNotice("com.proxai.hermes", setOf("hub")))
        assertTrue(!ShellLogic.acceptHubNotice("com.evil.app", setOf("hub")))
        assertTrue(!ShellLogic.acceptHubNotice(null, setOf("hub")))                 // sender unknown (older Android)
        assertTrue(!ShellLogic.acceptHubNotice("com.proxai.hermes", setOf("pipeline")))
        assertTrue(!ShellLogic.acceptHubNotice("com.proxai.hermes", null))
    }

    @Test fun hubFeatureApiAndErrors() {
        assertTrue("hub" in ShellLogic.parseInfo("""{"id": "prox-hub", "features": ["hub"]}""").features)
        for (api in listOf("window.ProxAI.hub = {", "state: function(){ return hcall({op: 'state'}); }",
                "capture: function(text){ return hcall({op: 'act', name: 'capture'", "answer: function(id, allow, message, stop)",
                "n.hub(id, JSON.stringify(o));", "o.op = String(op); return hcall(o);",
                "save: function(session, key)", "n.hubSave(id, JSON.stringify({session: hs(session), key: hs(key)}));"))
            assertTrue(api, ShellLogic.SHIM.contains(api))
        assertEquals("mutual-nda.md", ShellLogic.safeFileName("plugins/x/templates/mutual-nda.md"))
        assertEquals("a_b.pdf", ShellLogic.safeFileName("a:b.pdf"))
        assertEquals("file", ShellLogic.safeFileName("..."))
        assertEquals("file", ShellLogic.safeFileName(null))
        fun o(msg: String) = org.json.JSONObject(ShellLogic.hubError(msg))
        assertEquals("not_declared", o("This app wasn't built with the 'hub' feature.").getString("code"))
        assertEquals("ProxMobile is too old for Prox Hub (needs 0.9.10)", o("unknown method").getString("error"))
        assertEquals("bad_request", o("That request is too big").getString("code"))
    }

    @Test fun docsFeatureApiAndErrors() {
        assertTrue("docs" in ShellLogic.parseInfo("""{"id": "proxdocs", "features": ["docs"]}""").features)
        for (api in listOf("window.ProxAI.docs = {", "status: function(){ return kcall({op: 'status'}); }",
                "unlock: function(){ return kcall(null); }", "ask: function(question){ return kcall({op: 'ask', question: txt(question)}); }",
                "load: function(){ return kcall({op: 'load'}); }", "lock: function(){ return kcall({op: 'lock'}); }",
                "n.docs(id, JSON.stringify(o)); else n.docsUnlock(id);"))
            assertTrue(api, ShellLogic.SHIM.contains(api))
        assertTrue(!ShellLogic.SHIM.contains("password:") && !ShellLogic.SHIM.contains("password'"))   // never sends one
        fun o(msg: String) = org.json.JSONObject(ShellLogic.docsError(msg))
        assertEquals("not_declared", o("This app wasn't built with the 'docs' feature.").getString("code"))
        assertEquals("proxmobile_missing", o("ProxMobile isn't installed or is too old (needs 0.9.8)").getString("code"))
        assertEquals("proxmobile_too_old", o("unknown method").getString("code"))
        assertEquals("ProxMobile is too old for proxDocs (needs 0.9.8)", o("unknown method").getString("error"))
        assertEquals("busy", o("proxDocs is already asking for the password").getString("code"))
        assertEquals("not_trusted", o("This app isn't signed with the ProxAI apps key").getString("code"))
        assertEquals(false, o("x").getBoolean("ok"))
    }

    @Test fun studyFeatures() {
        assertEquals(setOf("voice", "listen", "files"),
            ShellLogic.parseInfo("""{"features":["voice","listen","files","microphone"]}""").features)
        for (api in listOf("voice: function", "n.voice(id, String(t))", "stopVoice: function", "n.listen(id, JSON.stringify(opts || {}))",
                "stopListening: function", "window.ProxAI.tutor = {", "n.tutor(id, JSON.stringify({method: method",
                "upload: function(id, file, onProgress)", "CH = 192000")) assertTrue(api, ShellLogic.SHIM.contains(api))
        assertEquals(setOf("tutor"), ShellLogic.parseInfo("""{"features":["tutor"]}""").features)
        // Study Coach 0.3-0.6: Learn mode, problems, trust, code critic.
        for (api in listOf("today: function", "lesson: function", "teachBack: function", "chat: function(id, o, onText)",
                "function follow(id, tid, rid, onText)", "problems: function", "help: function(id, pid, o, onText)",
                "dispute: function", "insights: function", "reflect: function", "saveCheckin: function",
                "codeReview: function", "setCoding: function")) assertTrue(api, ShellLogic.SHIM.contains(api))
    }

    @Test fun acceptToMimes() {
        assertEquals(listOf("application/pdf", "text/plain"), ShellLogic.acceptMimes(listOf(".pdf, .txt")))
        assertEquals(listOf("image/*"), ShellLogic.acceptMimes(listOf("image/*")))
        assertEquals(emptyList<String>(), ShellLogic.acceptMimes(listOf("")))
        assertEquals(emptyList<String>(), ShellLogic.acceptMimes(listOf(".pdf", ".weird")))   // unknown = any file
    }

    @Test fun listenOptionsAreClamped() {
        assertEquals(2000L, ShellLogic.listenOptions(null).silenceMs)
        assertEquals(800L, ShellLogic.listenOptions("""{"silenceMs":10}""").silenceMs)
        assertEquals(8000L, ShellLogic.listenOptions("""{"silenceMs":99999}""").silenceMs)
        assertEquals(listOf("mitosis", "ATP"), ShellLogic.listenOptions("""{"hints":["mitosis"," ATP ",""]}""").hints)
        assertEquals(2000L, ShellLogic.listenOptions("not json").silenceMs)
    }

    @Test fun infoDefaults() {
        val i = ShellLogic.parseInfo("{}")
        assertEquals("ProxAI app", i.name)
        assertEquals(emptySet<String>(), i.features)
        assertEquals(ShellLogic.BRAND, i.color)
    }

    @Test fun assetPathsStayInsideWww() {
        assertEquals("www/index.html", ShellLogic.assetPath("/"))
        assertEquals("www/index.html", ShellLogic.assetPath(null))
        assertEquals("www/js/app.js", ShellLogic.assetPath("/js/app.js"))
        assertEquals("www/sub/index.html", ShellLogic.assetPath("/sub/"))
        assertEquals("www/x", ShellLogic.assetPath("//x"))   // extra leading slashes are harmless
        for (bad in listOf("/../proxai-app.json", "/js/../../x", "/.hidden", "/a//b", "/a/./b"))
            assertNull(bad, ShellLogic.assetPath(bad))
    }

    @Test fun mimeTypes() {
        assertEquals("text/javascript", ShellLogic.mimeType("www/a.JS"))
        assertEquals("image/svg+xml", ShellLogic.mimeType("www/i.svg"))
        assertEquals("application/octet-stream", ShellLogic.mimeType("www/blob"))
    }

    @Test fun shimGoesFirstInHead() {
        val out = ShellLogic.injectShim("<html><head><title>x</title></head><body><script>ProxAI.toast('hi')</script></body></html>")
        assertTrue(out.indexOf("window.ProxAI") < out.indexOf("<title>"))
        assertTrue(ShellLogic.injectShim("<p>no head</p>").startsWith("<script>"))
        assertTrue(ShellLogic.SHIM.contains("pcStats: function()"))
    }

    // PM-HUBFIX (shell kit 12): every way the call to ProxMobile's door can fail says what really failed.
    @Test fun proxMobileAnswers() {
        assertEquals(true to """{"ok":true}""", ShellLogic.readAnswer(true, """{"ok":true}""", null))
        // ContentResolver.call gave null (ProxMobile's process stopped, or the answer was over Binder's limit)
        assertEquals(false to ShellLogic.NO_ANSWER, ShellLogic.readAnswer(false, null, null))
        // a Bundle with neither "json" nor "error", or a blank json
        assertEquals(false to ShellLogic.EMPTY_ANSWER, ShellLogic.readAnswer(true, null, null))
        assertEquals(false to ShellLogic.EMPTY_ANSWER, ShellLogic.readAnswer(true, "  ", null))
        // ProxMobile's own refusal passes through as it is
        val locked = "ProxAI is locked. Open ProxMobile and unlock it, then try again."
        assertEquals(false to locked, ShellLogic.readAnswer(true, null, locked))
        assertTrue(ShellLogic.readAnswer(true, null, "").second.contains("without saying why"))
        fun hub(msg: String) = org.json.JSONObject(ShellLogic.hubError(msg)).getString("code")
        assertEquals("proxmobile_no_answer", hub(ShellLogic.NO_ANSWER))
        assertEquals("proxmobile_empty", hub(ShellLogic.EMPTY_ANSWER))
        assertEquals("unreachable", hub("Can't reach Prox Dump sync on the PC (is the PC on and on the tailnet?)"))
    }

    // State + full right after an act: never two Prox Hub calls in Binder at once, and none is refused or dropped.
    @Test fun hubCallsGoOneAtATime() {
        val gate = ShellLogic.CallGate()
        val inside = java.util.concurrent.atomic.AtomicInteger()
        val most = java.util.concurrent.atomic.AtomicInteger()
        val done = java.util.Collections.synchronizedList(mutableListOf<Int>())
        val threads = (1..8).map { n ->
            Thread {
                val r = gate.run {
                    most.accumulateAndGet(inside.incrementAndGet()) { a, b -> maxOf(a, b) }
                    Thread.sleep(15)
                    inside.decrementAndGet()
                    n
                }
                done.add(r)
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join(5000) }
        assertEquals(1, most.get())
        assertEquals((1..8).toSet(), done.toSet())
        assertEquals("x", gate.run { "x" })   // free again
        runCatching { gate.run { throw IllegalStateException("boom") } }
        assertEquals("y", gate.run { "y" })   // a failed call doesn't keep the gate shut
    }

    @Test fun proxMobileCallThrew() {
        // No provider found: Android throws IllegalArgumentException("Unknown URI ..."); the version check tells apart
        // a ProxMobile this app can't see (missing, or hidden by package visibility) from one without the door.
        val unknown = IllegalArgumentException("Unknown URI content://com.proxai.hermes.pcstats")
        assertEquals(ShellLogic.HIDDEN, ShellLogic.callFailed(unknown, null))
        assertEquals(ShellLogic.HIDDEN,
            ShellLogic.callFailed(IllegalArgumentException("Unknown authority com.proxai.hermes.pcstats"), null))
        assertTrue(ShellLogic.callFailed(unknown, null).contains("package visibility"))
        val old = ShellLogic.callFailed(unknown, "0.6.0")
        assertEquals("ProxMobile 0.6.0 is installed but has no door for apps (needs 0.7.0).", old)
        assertEquals("Calling ProxMobile failed (SecurityException: Permission Denial).",
            ShellLogic.callFailed(SecurityException("Permission Denial"), "0.9.13"))
        assertEquals("Calling ProxMobile failed (IllegalStateException).", ShellLogic.callFailed(IllegalStateException(), null))
        fun hub(msg: String) = org.json.JSONObject(ShellLogic.hubError(msg))
        assertEquals("proxmobile_missing", hub(ShellLogic.HIDDEN).getString("code"))
        assertEquals(ShellLogic.HIDDEN, hub(ShellLogic.HIDDEN).getString("error"))
        assertEquals("proxmobile_too_old", hub(old).getString("code"))
        assertEquals("proxmobile_error", hub(ShellLogic.callFailed(SecurityException("x"), null)).getString("code"))
        // ProxMobile 0.9.13's own failures and its size cap
        assertEquals("proxmobile_error", hub("ProxMobile failed on that call (NullPointerException)").getString("code"))
        assertEquals("proxmobile_error",
            hub("ProxMobile ran out of memory on that answer (too large for the phone)").getString("code"))
        assertEquals("too_large",
            hub("That answer is too large for Android's app bridge (600K characters, the most is 400K)").getString("code"))
        // ProxMobile's app lock never reached the PC: "locked", not "unreachable" (which an app shows as Offline)
        assertEquals("locked", hub("ProxAI is locked. Open ProxMobile and unlock it, then try again.").getString("code"))
        assertEquals("busy", hub("ProxMobile is still starting. Try again in a moment.").getString("code"))
        assertEquals("ProxMobile is too old for Prox Hub (needs 0.9.10)", hub("unknown method").getString("error"))
        // docsUnlock's own text (no ProxMobile activity to start) keeps its code
        assertEquals("proxmobile_missing", org.json.JSONObject(
            ShellLogic.docsError("ProxMobile isn't installed or is too old (needs 0.9.8)")).getString("code"))
    }
}

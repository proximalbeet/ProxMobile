package com.proxai.hermes

import com.proxai.hermes.data.DumpsyncClient
import com.proxai.hermes.data.HubRelay
import com.proxai.hermes.data.PcNotice
import com.proxai.hermes.data.PcStatsProvider
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** ProxAI.dumpsync through ProxMobile, against a mocked proxai-dumpsync plugin (MockWebServer). */
class DumpsyncTest {
    private lateinit var plugin: MockWebServer
    private lateinit var client: DumpsyncClient
    private val token = "t".repeat(43)

    @Before fun setUp() {
        plugin = MockWebServer()
        plugin.start()
        client = DumpsyncClient(plugin.url("/dumpsync").toString(), token)
    }

    @After fun tearDown() = plugin.shutdown()

    private fun answer(code: Int, body: String) = plugin.enqueue(MockResponse().setResponseCode(code).setBody(body))
    private fun call(vararg kv: Pair<String, Any>) = JSONObject(client.call(JSONObject(kv.toMap()).toString()))

    @Test fun listSendsSinceAndTokenAndReturnsNotes() {
        answer(200, """{"ok":true,"server_time":"2026-10-26T19:00:00-04:00","since":"2026-10-26T13:00:00-04:00","count":1,
            "dumps":[{"id":"05-Brain-Dump/idea.md","title":"Idea","type":"brain-dump","body":"x\n","tags":["brain-dump"],
            "created":"2026-10-26T14:00:00-04:00","updated":"2026-10-26T14:00:00-04:00","app_status":"new","change":"new"}],"removed":[]}""")
        val r = call("op" to "list", "since" to "2026-10-26T17:00:00Z")
        val req = plugin.takeRequest()
        assertEquals("GET", req.method)
        assertEquals("/dumpsync/dumps", req.requestUrl!!.encodedPath)
        assertEquals("2026-10-26T17:00:00Z", req.requestUrl!!.queryParameter("since"))
        assertEquals("Bearer $token", req.getHeader("Authorization"))
        assertEquals("2026-10-26T19:00:00-04:00", r.getString("server_time"))
        val note = r.getJSONArray("dumps").getJSONObject(0)
        for (k in listOf("id", "title", "type", "body", "tags", "created", "updated", "app_status")) assertTrue(k, note.has(k))
    }

    @Test fun plusOffsetSurvivesTheQuery() {
        answer(200, """{"ok":true,"server_time":"x","count":0,"dumps":[],"removed":[]}""")
        call("op" to "list", "since" to "2026-10-26T17:00:00+02:00")
        val req = plugin.takeRequest()
        assertTrue(req.path!!.contains("%2B02"))                       // not a space on the server
        assertEquals("2026-10-26T17:00:00+02:00", req.requestUrl!!.queryParameter("since"))
    }

    @Test fun listWithoutSinceGetsEverything() {
        answer(200, """{"ok":true,"server_time":"x","count":0,"dumps":[],"removed":[]}""")
        call("op" to "list")
        assertEquals("/dumpsync/dumps", plugin.takeRequest().path)
    }

    @Test fun setStatusPostsToTheEncodedId() {
        answer(200, """{"ok":true,"id":"05-Brain-Dump/my idea.md","app_status":"done","updated":"2026-10-26T19:00:00-04:00"}""")
        val r = call("op" to "setStatus", "id" to "05-Brain-Dump/my idea.md", "status" to "done")
        val req = plugin.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/dumpsync/dumps/05-Brain-Dump%2Fmy%20idea.md/status", req.path)
        assertEquals("""{"status":"done"}""", req.body.readUtf8())
        assertEquals("done", r.getString("app_status"))
    }

    @Test fun trashListsHeldItems() {
        answer(200, """{"ok":true,"server_time":"x","trash_hours":24,"items":[{"trash_id":"20261026T230000Z-0a1b2c3d",
            "id":"05-Brain-Dump/idea.md","deleted_at":"2026-10-26T19:00:00-04:00","expires_at":"2026-10-27T19:00:00-04:00","recoverable":true}]}""")
        val item = call("op" to "trash").getJSONArray("items").getJSONObject(0)
        assertEquals("/dumpsync/trash", plugin.takeRequest().path)
        assertEquals("2026-10-27T19:00:00-04:00", item.getString("expires_at"))
        assertTrue(item.has("deleted_at"))
    }

    @Test fun recoverPostsAndReturnsReviewed() {
        answer(200, """{"ok":true,"id":"02-Projects/p.md","trash_id":"t","app_status":"reviewed","updated":"x"}""")
        val r = call("op" to "recover", "id" to "02-Projects/p.md")
        val req = plugin.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/dumpsync/dumps/02-Projects%2Fp.md/recover", req.path)
        assertEquals("reviewed", r.getString("app_status"))
    }

    @Test fun summaryNeedsSinceAndReturnsCounts() {
        answer(200, """{"ok":true,"server_time":"x","since":"y","new":{"brain-dump":3,"project":0,"total":3},
            "updated":{"brain-dump":0,"project":1,"total":1},"message":"3 new brain dumps, 1 updated project"}""")
        val r = call("op" to "summary", "since" to "2026-10-26")
        assertEquals("/dumpsync/summary?since=2026-10-26", plugin.takeRequest().path)
        assertEquals(3, r.getJSONObject("new").getInt("total"))
        val missing = call("op" to "summary")
        assertEquals("bad_request", missing.getString("code"))
        assertEquals(1, plugin.requestCount)       // refused before reaching the PC
    }

    @Test fun structuredErrors() {
        answer(401, """{"ok":false,"error":"missing or wrong bearer token"}""")
        call("op" to "trash").let { assertEquals("unauthorized", it.getString("code")); assertEquals(401, it.getInt("status")) }
        answer(410, """{"ok":false,"error":"deleted more than 24 hours ago; it can't be recovered"}""")
        call("op" to "recover", "id" to "05-Brain-Dump/a.md").let {
            assertEquals("expired", it.getString("code")); assertTrue(it.getString("error").contains("24 hours")) }
        answer(404, """{"ok":false,"error":"05-Brain-Dump/a.md isn't in the trash"}""")
        call("op" to "recover", "id" to "05-Brain-Dump/a.md").let {
            assertEquals("not_found", it.getString("code")); assertEquals("05-Brain-Dump/a.md isn't in the trash", it.getString("error")) }
        answer(404, "404 page not found")           // tailscale serve without /dumpsync
        assertEquals("unreachable", call("op" to "trash").getString("code"))
        answer(502, "")                             // tailscale serve, server down
        assertEquals("unreachable", call("op" to "trash").getString("code"))
        answer(403, """{"ok":false,"error":"blocked: 01-Personal is off limits"}""")
        assertEquals("blocked", call("op" to "setStatus", "id" to "01-Personal/x.md", "status" to "done").getString("code"))
        answer(409, """{"ok":false,"error":"a note already exists at 05-Brain-Dump/a.md"}""")
        assertEquals("conflict", call("op" to "recover", "id" to "05-Brain-Dump/a.md").getString("code"))
        answer(503, """{"ok":false,"error":"The vault is busy"}""")
        assertEquals("busy", call("op" to "trash").getString("code"))
    }

    @Test fun unreachableWhenThePcIsOff() {
        plugin.shutdown()
        val r = call("op" to "list")
        assertEquals("unreachable", r.getString("code"))
        assertFalse(r.has("status"))
    }

    @Test fun badInputNeverReachesThePc() {
        for (bad in listOf(
            JSONObject().put("op", "delete"),
            JSONObject().put("op", "setStatus").put("id", "05-Brain-Dump/a.md").put("status", "gone"),
            JSONObject().put("op", "setStatus").put("status", "done"),
            JSONObject().put("op", "recover").put("id", "a\nb"),
            JSONObject().put("op", "list").put("since", "yesterday"),
        )) assertEquals(bad.toString(), "bad_request", JSONObject(client.call(bad.toString())).getString("code"))
        assertEquals("bad_request", JSONObject(client.call("not json")).getString("code"))
        assertEquals(0, plugin.requestCount)
    }

    @Test fun notConfiguredWithoutToken() {
        val r = JSONObject(DumpsyncClient(plugin.url("/").toString(), "").call("""{"op":"list"}"""))
        assertEquals("not_configured", r.getString("code"))
        assertEquals(0, plugin.requestCount)
    }

    @Test fun tokenAndUrlNeverInTheAnswer() {
        answer(401, """{"ok":false,"error":"missing or wrong bearer token"}""")
        val out = client.call("""{"op":"list"}""")
        assertFalse(out.contains(token))
        assertFalse(out.contains(plugin.hostName + ":" + plugin.port))
    }

    @Test fun appsWithoutTheFeatureAreRefused() {
        assertEquals("This app wasn't built with the 'dumpsync' feature.",
            PcStatsProvider.featureRefusal("dumpsync", setOf("scout", "pc-stats")))
        assertTrue(PcStatsProvider.featureRefusal("dumpsync", null)!!.contains("Can't read"))
        assertNull(PcStatsProvider.featureRefusal("dumpsync", setOf("dumpsync")))
        // Declaring dumpsync doesn't open other doors.
        assertEquals("This app wasn't built with the 'scout' feature.", PcStatsProvider.featureRefusal("scout", setOf("dumpsync")))
    }

    @Test fun errorCodes() {
        assertEquals("unauthorized", DumpsyncClient.codeFor(401, true))
        assertEquals("expired", DumpsyncClient.codeFor(410, true))
        assertEquals("not_found", DumpsyncClient.codeFor(404, true))
        assertEquals("unreachable", DumpsyncClient.codeFor(404, false))
        assertEquals("busy", DumpsyncClient.codeFor(503, true))
        assertEquals("unreachable", DumpsyncClient.codeFor(503, false))
        assertEquals("server_error", DumpsyncClient.codeFor(500, true))
    }

    // ---- Daily Log (ProxAI.daily, 0.7.2) ----

    private fun daily(vararg kv: Pair<String, Any>) = JSONObject(client.daily(JSONObject(kv.toMap()).toString()))

    @Test fun dailyGetSendsDateAndToken() {
        answer(200, """{"ok":true,"date":"2026-10-25","weekday":"Sunday","exists":true,"focus":"x","tasks":[],"notes":[],
            "evening":"","sections":[],"pending":0,"messages":[{"id":1,"time":"2026-10-25T09:35:00","title":"Today","text":"t"}],"server_time":"x"}""")
        val r = daily("op" to "get", "date" to "2026-10-25")
        val req = plugin.takeRequest()
        assertEquals("GET", req.method)
        assertEquals("/dumpsync/daily", req.requestUrl!!.encodedPath)
        assertEquals("2026-10-25", req.requestUrl!!.queryParameter("date"))
        assertEquals("Bearer $token", req.getHeader("Authorization"))
        assertEquals("Today", r.getJSONArray("messages").getJSONObject(0).getString("title"))
    }

    @Test fun dailyTodayHasNoDate() {
        answer(200, """{"ok":true,"date":"2026-10-26"}""")
        daily("op" to "get")
        assertEquals("/dumpsync/daily", plugin.takeRequest().path)
    }

    @Test fun dailyAddPostsSectionAndItems() {
        answer(201, """{"ok":true,"date":"2026-10-26","section":"tasks","added":["Buy milk"],"file":"_inbox/x.md","merges_within_s":120}""")
        val r = daily("op" to "add", "section" to "tasks", "items" to org.json.JSONArray(listOf("Buy milk")))
        val req = plugin.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/dumpsync/daily", req.requestUrl!!.encodedPath)
        val body = JSONObject(req.body.readUtf8())
        assertEquals("tasks", body.getString("section"))
        assertEquals("Buy milk", body.getJSONArray("items").getString(0))
        assertFalse(body.has("date"))
        assertEquals(true, r.getBoolean("ok"))
    }

    @Test fun dailyServerRefusalKeepsItsMessage() {
        answer(400, """{"ok":false,"error":"nothing to add"}""")
        val r = daily("op" to "add", "section" to "notes", "items" to " ")
        assertEquals("bad_request", r.getString("code"))
        assertEquals("nothing to add", r.getString("error"))
        assertEquals(400, r.getInt("status"))
    }

    @Test fun dailyBadInputNeverReachesThePc() {
        for (bad in listOf(
            JSONObject().put("op", "Bad Op!"),
            JSONObject().put("op", "x"),
            JSONObject().put("op", "get").put("date", "yesterday"),
            JSONObject().put("op", "add").put("section", "evening").put("items", "x"),
            JSONObject().put("op", "add").put("section", "tasks").put("items", org.json.JSONArray(listOf(1, 2))),
            JSONObject().put("op", "add").put("section", "tasks"),
        )) assertEquals(bad.toString(), "bad_request", JSONObject(client.daily(bad.toString())).getString("code"))
        assertEquals("bad_request", JSONObject(client.daily("x".repeat(40_000))).getString("code"))
        assertEquals(0, plugin.requestCount)
    }

    @Test fun dailyNotConfiguredAndUnreachable() {
        val none = DumpsyncClient(plugin.url("/dumpsync").toString(), "")
        assertEquals("not_configured", JSONObject(none.daily("""{"op":"get"}""")).getString("code"))
        plugin.shutdown()
        assertEquals("unreachable", JSONObject(client.daily("""{"op":"get"}""")).getString("code"))
    }

    @Test fun dailyNeedsItsOwnFeature() {
        assertEquals("This app wasn't built with the 'daily' feature.", PcStatsProvider.featureRefusal("daily", setOf("dumpsync")))
        assertNull(PcStatsProvider.featureRefusal("daily", setOf("daily")))
    }

    @Test fun dailyOtherOpsPassThroughToThePc() {
        answer(200, """{"ok":true,"date":"2026-10-27","habit":{"id":"water","count":3},"habits":[]}""")
        val r = daily("op" to "habit", "id" to "water", "action" to "add", "n" to 2)
        val req = plugin.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/dumpsync/daily/habit", req.requestUrl!!.encodedPath)
        val body = JSONObject(req.body.readUtf8())
        assertFalse(body.has("op"))
        assertEquals("water", body.getString("id"))
        assertEquals(2, body.getInt("n"))
        assertEquals(3, r.getJSONObject("habit").getInt("count"))
        answer(404, """{"ok":false,"error":"no such route"}""")
        assertEquals("not_found", daily("op" to "habit-history-v9").getString("code"))
        assertEquals("/dumpsync/daily/habit-history-v9", plugin.takeRequest().requestUrl!!.encodedPath)
    }

    // ---- Pipeline (ProxAI.pipeline, 0.7.7) ----

    private val owner = DumpsyncClient.BOARDROOM_APP                       // an owner app: every op goes to the PC
    private fun pipeline(vararg kv: Pair<String, Any>) =
        JSONObject(client.pipeline(JSONObject(kv.toMap()).toString(), owner, true))

    @Test fun pipelineOpsPassThroughWithTheToken() {
        answer(200, """{"ok":true,"server_time":"x","lanes":[{"id":"gig","code":"G"}],"items":[{"code":"G1","stage":"sent"}],"due":0}""")
        val r = pipeline("op" to "list", "lane" to "gig", "closed" to false)
        val req = plugin.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/dumpsync/pipeline/list", req.requestUrl!!.encodedPath)
        assertEquals("Bearer $token", req.getHeader("Authorization"))
        val body = JSONObject(req.body.readUtf8())
        assertFalse(body.has("op"))
        assertEquals("gig", body.getString("lane"))
        assertEquals("G1", r.getJSONArray("items").getJSONObject(0).getString("code"))
        answer(200, """{"ok":true,"item":{"code":"G1","stage":"replied"}}""")
        pipeline("op" to "stage", "id" to "G1", "stage" to "replied")
        assertEquals("/dumpsync/pipeline/stage", plugin.takeRequest().requestUrl!!.encodedPath)
        answer(200, """{"ok":true,"decisions":[]}""")                           // a later op, no ProxMobile change
        assertTrue(pipeline("op" to "decisions").has("decisions"))
        assertEquals("/dumpsync/pipeline/decisions", plugin.takeRequest().requestUrl!!.encodedPath)
    }

    @Test fun pipelineErrors() {
        answer(404, """{"ok":false,"error":"nothing in the pipeline is called G9"}""")
        pipeline("op" to "item", "id" to "G9").let {
            assertEquals("not_found", it.getString("code")); assertEquals("nothing in the pipeline is called G9", it.getString("error")) }
        answer(400, """{"ok":false,"error":"G1 (Gigs) can be: interested, proposal"}""")
        assertEquals("bad_request", pipeline("op" to "stage", "id" to "G1", "stage" to "launched").getString("code"))
        answer(401, """{"ok":false,"error":"missing or wrong bearer token"}""")
        assertEquals("unauthorized", pipeline("op" to "list").getString("code"))
        assertEquals(3, plugin.requestCount)
        for (bad in listOf("""{"op":"../dumps"}""", """{"op":"List"}""", """{"op":""}""", "not json", "x".repeat(40_000)))
            assertEquals(bad.take(20), "bad_request", JSONObject(client.pipeline(bad, owner, true)).getString("code"))
        assertEquals(3, plugin.requestCount)                                    // refused before the PC
        val none = DumpsyncClient(plugin.url("/dumpsync").toString(), "")
        assertEquals("not_configured", JSONObject(none.pipeline("""{"op":"list"}""", owner, true)).getString("code"))
        plugin.shutdown()
        assertEquals("unreachable", pipeline("op" to "list").getString("code"))
    }

    @Test fun pipelineNeedsItsOwnFeature() {
        assertEquals("This app wasn't built with the 'pipeline' feature.",
            PcStatsProvider.featureRefusal("pipeline", setOf("dumpsync", "daily", "scout")))
        assertNull(PcStatsProvider.featureRefusal("pipeline", setOf("pipeline")))
        assertEquals("This app wasn't built with the 'dumpsync' feature.", PcStatsProvider.featureRefusal("dumpsync", setOf("pipeline")))
    }

    // ---- Prox Hub (ProxAI.hub, 0.9.10) ----

    private val hubApp = DumpsyncClient.HUB_APP
    private fun hub(vararg kv: Pair<String, Any>) = JSONObject(client.hub(JSONObject(kv.toMap()).toString(), hubApp, true))

    @Test fun hubOpsPassThroughWithTheToken() {
        answer(200, """{"ok":true,"needs":2,"inbox":[]}""")
        val r = hub("op" to "state")
        val req = plugin.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/dumpsync/hub/state", req.requestUrl!!.encodedPath)
        assertEquals("Bearer $token", req.getHeader("Authorization"))
        assertEquals("com.proxai.apps.prox_hub", req.getHeader("X-ProxAI-App"))
        assertNull(req.getHeader("X-ProxAI-Owner-Token"))                  // none set: none sent
        assertFalse(JSONObject(req.body.readUtf8()).has("op"))
        assertEquals(2, r.getInt("needs"))
        answer(200, """{"ok":true,"what":"captured: x"}""")
        hub("op" to "act", "name" to "capture", "args" to JSONObject(mapOf("text" to "x")))
        val r2 = plugin.takeRequest()
        assertEquals("/dumpsync/hub/act", r2.requestUrl!!.encodedPath)
        assertEquals("capture", JSONObject(r2.body.readUtf8()).getString("name"))
    }

    @Test fun hubErrors() {
        answer(400, """{"ok":false,"error":"that question was already answered or timed out"}""")
        assertEquals("bad_request", hub("op" to "act", "name" to "answer").getString("code"))
        answer(401, """{"ok":false,"error":"missing or wrong bearer token"}""")
        assertEquals("unauthorized", hub("op" to "state").getString("code"))
        assertEquals(2, plugin.requestCount)
        for (bad in listOf("""{"op":"../dumps"}""", """{"op":"State"}""", "not json", "x".repeat(40_000)))
            assertEquals(bad.take(20), "bad_request", JSONObject(client.hub(bad, hubApp, true)).getString("code"))
        assertEquals(2, plugin.requestCount)
        val none = DumpsyncClient(plugin.url("/dumpsync").toString(), "")
        assertEquals("not_configured", JSONObject(none.hub("""{"op":"state"}""", hubApp, true)).getString("code"))
    }

    // ---- HUB-APP-ONLY (0.9.15): only the Prox Hub app, by Android's name for the caller ----

    private val ownerToken = "o".repeat(40)
    private fun withOwnerToken(t: String = ownerToken) =
        DumpsyncClient(plugin.url("/dumpsync").toString(), token, ownerToken = t)

    @Test fun hubRefusesEveryOtherAppBeforeThePc() {
        val c = withOwnerToken()
        for (other in listOf("com.proxai.apps.prox_dump", "com.proxai.apps.prox_hub2", "com.proxai.apps.prox-hub",
                "com.proxai.apps.PROX_HUB", "com.proxai.apps.", "com.proxai.hermes", "", null,
                DumpsyncClient.BOARDROOM_APP)) {                           // an owner app, but not the Hub app
            for (op in listOf("state", "act", "file")) {
                val r = JSONObject(c.hub("""{"op":"$op","name":"answer","app":"$hubApp"}""", other, true))
                assertEquals("$other $op", "blocked", r.getString("code"))
                assertEquals(DumpsyncClient.NOT_HUB_APP, r.getString("error"))
            }
        }
        assertEquals(0, plugin.requestCount)                               // nothing reached the PC, no token left
        assertNull(DumpsyncClient.hubRefusal("com.proxai.apps.prox_hub", true))
    }

    @Test fun hubAppSendsTheOwnerToken() {
        val c = withOwnerToken()
        answer(200, """{"ok":true}""")
        c.hub("""{"op":"act","name":"answer"}""", hubApp, true)
        val req = plugin.takeRequest()
        assertEquals(ownerToken, req.getHeader("X-ProxAI-Owner-Token"))
        assertEquals(hubApp, req.getHeader("X-ProxAI-App"))
        assertNull(req.getHeader("X-ProxAI-Hub-Token"))                    // the round-1 name is gone
        assertFalse(JSONObject(req.body.readUtf8()).toString().contains(ownerToken))
    }

    @Test fun dumpsyncAndDailyCallsStayAsTheyWere() {
        val c = withOwnerToken()
        answer(200, """{"ok":true,"date":"2026-11-16"}""")
        c.daily("""{"op":"get"}""")
        answer(200, """{"ok":true,"server_time":"x","count":0,"dumps":[],"removed":[]}""")
        c.call("""{"op":"list"}""")
        repeat(2) {
            val o = plugin.takeRequest()
            assertNull(o.path, o.getHeader("X-ProxAI-Owner-Token"))
            assertNull(o.path, o.getHeader("X-ProxAI-App"))
            assertEquals(o.path, "Bearer $token", o.getHeader("Authorization"))
        }
    }

    @Test fun ownerTokenWithBadCharactersIsCaughtHere() {
        val c = withOwnerToken("abc def\n")
        for (r in listOf(JSONObject(c.hub("""{"op":"state"}""", hubApp, true)),
                JSONObject(c.pipeline("""{"op":"stage","id":"G1"}""", owner, true)))) {
            assertEquals("bad_request", r.getString("code"))
            assertTrue(r.getString("error").contains("Owner token"))
            assertFalse(r.toString().contains("abc def"))                   // never echoed back
        }
        assertEquals(0, plugin.requestCount)
        // an app that never gets the token isn't stopped by it
        answer(200, """{"ok":true,"items":[]}""")
        assertTrue(JSONObject(c.pipeline("""{"op":"list"}""", "com.proxai.apps.prox_dump")).getBoolean("ok"))
        assertNull(plugin.takeRequest().getHeader("X-ProxAI-Owner-Token"))
    }

    // ---- Pipeline by caller (BOARDROOM-DOOR, 0.9.15): summary reads for any app, the rest for owner apps ----

    private val otherApp = "com.proxai.apps.prox_dump"

    @Test fun ownerAppsAreTheHubAndBoardRoom() {
        assertEquals(setOf("com.proxai.apps.prox_hub", "com.proxai.apps.boardroom"), DumpsyncClient.OWNER_APPS)
    }

    @Test fun pipelineSendsTheCallersPackageOnEveryCall() {
        val c = withOwnerToken()
        for (caller in listOf(otherApp, DumpsyncClient.BOARDROOM_APP, hubApp)) {
            answer(200, """{"ok":true,"items":[]}""")
            c.pipeline("""{"op":"list","app":"com.proxai.apps.boardroom"}""", caller)
            val req = plugin.takeRequest()
            assertEquals(caller, req.getHeader("X-ProxAI-App"))         // never the "app" field in the request
        }
    }

    @Test fun pipelineOwnerTokenOnlyForOwnerApps() {
        val c = withOwnerToken()
        for (caller in listOf(DumpsyncClient.BOARDROOM_APP, hubApp)) {
            answer(200, """{"ok":true}""")
            c.pipeline("""{"op":"list"}""", caller, true)
            assertEquals(caller, ownerToken, plugin.takeRequest().getHeader("X-ProxAI-Owner-Token"))
        }
        for (caller in listOf(otherApp, "com.proxai.apps.boardroom2", "com.proxai.apps.board_room")) {
            answer(200, """{"ok":true}""")
            c.pipeline("""{"op":"list","app":"$owner","owner":true}""", caller, true)
            assertNull(caller, plugin.takeRequest().getHeader("X-ProxAI-Owner-Token"))
        }
    }

    @Test fun pipelineSummaryReadsPassForAnyApp() {
        val c = withOwnerToken()
        val reads = listOf("""{"op":"list","lane":"gig"}""", """{"op":"due"}""", """{"op":"clients"}""",
            """{"op":"client","client":"CL1"}""", """{"op":"money"}""")
        for (r in reads) {
            answer(200, """{"ok":true}""")
            assertTrue(r, JSONObject(c.pipeline(r, otherApp)).getBoolean("ok"))
            val op = JSONObject(r).getString("op")
            assertEquals("/dumpsync/pipeline/$op", plugin.takeRequest().requestUrl!!.encodedPath)
        }
    }

    @Test fun pipelineWritesAndDetailsRefusedForOtherApps() {
        val c = withOwnerToken()
        val refused = listOf("""{"op":"stage","id":"G1","stage":"replied"}""", """{"op":"log","id":"G1","text":"x"}""",
            """{"op":"next","id":"G1","action":"x"}""", """{"op":"idea","title":"x"}""",
            """{"op":"personal","id":"G1","personal":true}""", """{"op":"item","id":"G1"}""", """{"op":"cards"}""",
            """{"op":"mail"}""", """{"op":"agents"}""", """{"op":"week"}""", """{"op":"plans"}""",
            """{"op":"decisions"}""", """{"op":"client_update","client":"CL1"}""", """{"op":"client-update"}""",
            """{"op":"money","id":"G1"}""", """{"op":"money","id":null}""",
            """{"op":"stage","id":"G1","app":"com.proxai.apps.boardroom"}""")
        for (caller in listOf(otherApp, "com.proxai.apps.boardroom2", "com.proxai.hermes")) for (r in refused) {
            val a = JSONObject(c.pipeline(r, caller, true))   // even if the flag were set
            assertEquals("$caller $r", "blocked", a.getString("code"))
            assertEquals(DumpsyncClient.PIPELINE_READ_ONLY, a.getString("error"))
        }
        for (r in listOf("""{"op":"list"}""", """{"op":"stage","id":"G1"}"""))   // no caller: nothing at all
            assertEquals("blocked", JSONObject(c.pipeline(r, null)).getString("code"))
        assertEquals(0, plugin.requestCount)                               // nothing reached the PC
    }

    @Test fun ownerAppsGetEveryPipelineOp() {
        val c = withOwnerToken()
        for (caller in DumpsyncClient.OWNER_APPS) for (op in listOf("stage", "item", "cards", "client_update", "money")) {
            answer(200, """{"ok":true}""")
            assertTrue("$caller $op", JSONObject(c.pipeline("""{"op":"$op","id":"G1"}""", caller, true)).getBoolean("ok"))
            val req = plugin.takeRequest()
            assertEquals("/dumpsync/pipeline/$op", req.requestUrl!!.encodedPath)
            assertEquals(caller, req.getHeader("X-ProxAI-App"))
            assertEquals(ownerToken, req.getHeader("X-ProxAI-Owner-Token"))
        }
    }

    // ---- Round 4 (0.9.15) ----

    @Test fun tokensNeverFollowARedirectToAnotherHost() {
        val elsewhere = MockWebServer().apply { start() }
        try {
            val c = withOwnerToken()
            for (code in listOf(301, 302, 307, 308)) {
                plugin.enqueue(MockResponse().setResponseCode(code).setHeader("Location", elsewhere.url("/steal").toString()))
                val r = JSONObject(c.hub("""{"op":"act","name":"answer"}""", hubApp, true))
                assertFalse("$code", r.optBoolean("ok", true))                  // an error, not a followed answer
                assertEquals(code, r.getInt("status"))
            }
            plugin.enqueue(MockResponse().setResponseCode(302).setHeader("Location", elsewhere.url("/steal").toString()))
            c.pipeline("""{"op":"stage","id":"G1"}""", owner, true)
            plugin.enqueue(MockResponse().setResponseCode(302).setHeader("Location", elsewhere.url("/steal").toString()))
            c.call("""{"op":"list"}""")
            assertEquals(0, elsewhere.requestCount)                              // neither token went anywhere else
        } finally {
            elsewhere.shutdown()
        }
    }

    @Test fun theRefusalTextShownIsCapped() {
        answer(403, JSONObject().put("ok", false).put("error", "w".repeat(5000)).toString())
        assertEquals("w".repeat(DumpsyncClient.MAX_REFUSAL), hub("op" to "act", "name" to "accept").getString("error"))
        answer(403, JSONObject().put("ok", false).put("error", "p".repeat(5000)).toString())
        assertEquals(300, pipeline("op" to "stage", "id" to "G1").getString("error").length)
        answer(403, """{"ok":false,"error":"   "}""")                            // only spaces: the fallback line
        assertTrue(hub("op" to "state").getString("error").contains("Owner token"))
    }

    @Test fun pipeline403ShowsThePcsOwnText() {
        answer(403, """{"ok":false,"error":"Only BoardRoom and Prox Hub can change the pipeline."}""")
        val r = pipeline("op" to "stage", "id" to "G1", "stage" to "replied")
        assertEquals("blocked", r.getString("code"))
        assertEquals("Only BoardRoom and Prox Hub can change the pipeline.", r.getString("error"))
        answer(403, """{"ok":false}""")
        assertFalse(pipeline("op" to "list").getString("error").contains("folders"))
    }

    @Test fun hub403ShowsThePcsOwnText() {
        answer(403, """{"ok":false,"error":"Only the Prox Hub app can answer or accept."}""")
        val r = hub("op" to "act", "name" to "accept")
        assertEquals("blocked", r.getString("code"))
        assertEquals(403, r.getInt("status"))
        assertEquals("Only the Prox Hub app can answer or accept.", r.getString("error"))
        answer(403, """{"ok":false}""")                                    // no text: a hub line, not Prox Dump's
        val blank = hub("op" to "act", "name" to "accept").getString("error")
        assertTrue(blank, blank.contains("Owner token"))
        assertFalse(blank.contains("folders"))
        // Prox Dump's own 403 keeps its note-folder message
        answer(403, """{"ok":false,"error":"outside"}""")
        assertEquals("That note is outside the folders Prox Dump can use.",
            call("op" to "setStatus", "id" to "x.md", "status" to "done").getString("error"))
    }

    @Test fun hubRefusalNamesExactlyOneApp() {      // HubRelay.apps uses it too: notices go only to that app
        assertNull(DumpsyncClient.hubRefusal(DumpsyncClient.HUB_APP, true))
        assertEquals(DumpsyncClient.NOT_HUB_APP, DumpsyncClient.hubRefusal("com.proxai.apps.my_hub", true))
        assertEquals(DumpsyncClient.HUB_NOT_OWNER_SIGNED, DumpsyncClient.hubRefusal(DumpsyncClient.HUB_APP, false))
    }

    // ---- OWNER-SIGN (0.9.16): the owner apps' names alone are not enough; Android must say they're owner-signed ----

    @Test fun anUnsignedProxHubGetsNoProxHubAndNoToken() {
        val c = withOwnerToken()
        for (op in listOf("state", "act", "file")) {
            val r = JSONObject(c.hub("""{"op":"$op","name":"answer"}""", hubApp, owner = false))
            assertEquals(op, "blocked", r.getString("code"))
            assertEquals(DumpsyncClient.HUB_NOT_OWNER_SIGNED, r.getString("error"))
            assertFalse(r.toString().contains(ownerToken))
        }
        assertFalse(JSONObject(c.hub("""{"op":"state"}""", hubApp)).optBoolean("ok", false))   // the default is "not owner"
        assertEquals(0, plugin.requestCount)                               // nothing reached the PC
    }

    @Test fun unsignedOwnerAppsGetThePipelineSummaryOnlyAsOtherApps() {
        val c = withOwnerToken()
        for (caller in DumpsyncClient.OWNER_APPS) {
            answer(200, """{"ok":true,"items":[]}""")
            assertTrue(caller, JSONObject(c.pipeline("""{"op":"list"}""", caller, owner = false)).getBoolean("ok"))
            val req = plugin.takeRequest()
            assertEquals(caller, req.getHeader("X-ProxAI-App"))
            assertNull(caller, req.getHeader("X-ProxAI-Owner-Token"))        // no token without the signature
            for (r in listOf("""{"op":"stage","id":"G1"}""", """{"op":"item","id":"G1"}""", """{"op":"money","id":"G1"}""",
                    """{"op":"client_update","client":"CL1"}""")) {
                val a = JSONObject(c.pipeline(r, caller, owner = false))
                assertEquals("$caller $r", "blocked", a.getString("code"))
                assertEquals(DumpsyncClient.PIPELINE_READ_ONLY, a.getString("error"))
            }
        }
        assertEquals(2, plugin.requestCount)                               // only the two summary reads
        assertEquals(DumpsyncClient.PIPELINE_READ_ONLY,
            DumpsyncClient.pipelineRefusal(DumpsyncClient.BOARDROOM_APP, "stage", JSONObject(), owner = false))
        assertNull(DumpsyncClient.pipelineRefusal(DumpsyncClient.BOARDROOM_APP, "stage", JSONObject(), owner = true))
    }

    @Test fun aSignedOwnerAppWithNoTokenSetSendsNone() {
        answer(200, """{"ok":true}""")
        client.pipeline("""{"op":"stage","id":"G1"}""", owner, true)
        assertNull(plugin.takeRequest().getHeader("X-ProxAI-Owner-Token"))
    }

    @Test fun hubNeedsItsOwnFeature() {
        assertEquals("This app wasn't built with the 'hub' feature.", PcStatsProvider.featureRefusal("hub", setOf("dumpsync", "pipeline")))
        assertNull(PcStatsProvider.featureRefusal("hub", setOf("hub")))
    }

    @Test fun hubNoticesGoToTheHubAppWhenThereIsOne() {
        fun n(id: Int, kind: String) = PcNotice(id, "2026-11-08T15:00:00", kind, "t$id", "x")
        val all = listOf(n(1, "hub"), n(2, "rhythm"), n(3, "hub"), n(4, "build"))
        val (toApp, toProx) = HubRelay.split(all, haveApp = true)
        assertEquals(listOf(1, 3), toApp.map { it.id })
        assertEquals(listOf(2, 4), toProx.map { it.id })
        val (none, everything) = HubRelay.split(all, haveApp = false)      // no Prox Hub app yet: nothing is lost
        assertTrue(none.isEmpty()); assertEquals(4, everything.size)
    }
}

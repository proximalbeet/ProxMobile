package com.proxai.hermes

import android.content.ContextWrapper
import android.content.SharedPreferences
import com.proxai.hermes.data.AppStore
import com.proxai.hermes.data.ChatRepository
import com.proxai.hermes.data.HermesClient
import com.proxai.hermes.data.ModelRoute
import com.proxai.hermes.data.PcModeMonitor
import com.proxai.hermes.data.ReminderStore
import com.proxai.hermes.data.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * X1-PM (2026-11-16, Jarvis plan step 1): phone chat goes to Sonnet 5.5 by default, a saved "auto" no longer
 * sends chat to the local model, the model asked for is always a listed one, and the first word shows as soon
 * as it streams. The real ChatRepository against a mocked Hermes API on this machine (no network, no model).
 */
@OptIn(DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)
class ChatModelX1Test {
    private val main = newSingleThreadContext("x1-main")
    private val scope = CoroutineScope(SupervisorJob() + main)
    private val server = MockWebServer()
    private val chatBodies = CopyOnWriteArrayList<String>()
    private val otherPaths = CopyOnWriteArrayList<String>()
    @Volatile private var reply: () -> MockResponse = { sse("Hi") }
    private val prefs = MemPrefs()
    private lateinit var ctx: ContextWrapper

    private fun event(text: String) = "data: {\"choices\": [{\"delta\": {\"content\": \"$text\"}}]}\n\n"
    private fun sse(vararg words: String) = MockResponse().setHeader("Content-Type", "text/event-stream")
        .setBody(words.joinToString("") { event(it) } + "data: [DONE]\n\n")

    @Before fun setUp() {
        Dispatchers.setMain(main)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.path?.startsWith("/v1/chat/completions") != true) {
                    otherPaths += request.path.orEmpty()
                    return MockResponse().setResponseCode(404)
                }
                chatBodies += request.body.readUtf8()
                return reply()
            }
        }
        server.start()
        val dir = Files.createTempDirectory("x1chat").toFile()
        ctx = object : ContextWrapper(null) {
            override fun getFilesDir() = dir
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = prefs
        }
    }

    @After fun tearDown() {
        scope.cancel()
        server.shutdown()
        Dispatchers.resetMain()
        main.close()
    }

    private fun settings() = Settings(ctx).apply {
        serverUrl = server.url("/").toString()
        apiKey = "k".repeat(40)
    }

    private fun repo(s: Settings) = ChatRepository(ctx, s, HermesClient(s), AppStore(ctx), PcModeMonitor(s),
        ReminderStore(ctx), scope) { true }

    private fun <T> onMain(block: () -> T): T = runBlocking { withContext(main) { block() } }

    private fun idle(repo: ChatRepository) = runBlocking {
        withTimeout(10_000) { withContext(main) { delay(50); while (repo.busy.value) delay(20) } }
    }

    @Test fun aFreshPhoneChatsWithSonnet() {
        val s = settings()
        assertEquals("cloud", s.modelChoice)
        assertEquals(ModelRoute.SONNET, s.model)
        val r = repo(s)
        assertEquals(ModelRoute(ModelRoute.SONNET, "anthropic", local = false), r.currentRoute())
        onMain { r.send("hey") }
        idle(r)
        val body = JSONObject(chatBodies.single())
        assertEquals(ModelRoute.SONNET, body.getString("model"))
        assertEquals("anthropic", body.getString("provider"))
        assertTrue(body.getBoolean("stream"))
    }

    @Test fun aSavedAutoIsTheCloudModelAndNeverChecksThePcMode() {
        // A phone that had "Auto" and server mode before 0.9.14: chat now goes straight to the cloud model.
        prefs.edit().putString("model_choice", "auto").putString("cloud_model", ModelRoute.HAIKU).apply()
        val s = settings()
        assertEquals("cloud", s.modelChoice)
        val r = repo(s)
        assertEquals(ModelRoute.cloud(ModelRoute.HAIKU), r.currentRoute())
        onMain { r.send("hey") }
        idle(r)
        assertEquals(ModelRoute.HAIKU, JSONObject(chatBodies.single()).getString("model"))
        // No /voice/v1/mode or /voice/health probe before (or after) the message: nothing holds the first word.
        assertTrue("asked the PC: $otherPaths", otherPaths.none { it.startsWith("/voice") })
    }

    @Test fun theModelAskedForIsAlwaysOnTheList() {
        val s = settings()
        prefs.edit().putString("cloud_model", "gpt-5-typed-by-hand").putString("local_model", "llama:70b").apply()
        assertEquals(ModelRoute.SONNET, s.model)                       // a saved name not on the list reads as Sonnet
        assertEquals("qwen3.5:35b-a3b", s.localModel)                  // a saved local name not on the list: the first listed
        s.model = "made-up-model"                                      // ignored
        assertEquals(ModelRoute.SONNET, s.model)
        s.model = ModelRoute.OPUS
        assertEquals(ModelRoute.OPUS, s.model)
        s.localModel = "something else"
        assertEquals("qwen3.5:35b-a3b", s.localModel)
        prefs.edit().putString("cloud_model", "claude-fable-5-1").apply()
        assertEquals(ModelRoute.FABLE, s.model)                         // an older Fable choice reads as today's
        s.modelChoice = "auto"
        assertEquals("cloud", s.modelChoice)
        s.modelChoice = "local"
        assertEquals("local", s.modelChoice)
        // Every id the app can send is a listed one.
        val listed = ModelRoute.CLOUD_CHOICES.map { it.second } + ModelRoute.LOCAL_CHOICES.map { it.second }
        listOf("", "x", "auto", ModelRoute.HAIKU, "claude-fable-5-1").forEach { m ->
            assertTrue(ModelRoute.pick("cloud", m, "prox-ai", m).model in listed)
            assertTrue(ModelRoute.pick("local", m, "prox-ai", m).model in listed)
        }
    }

    @Test fun theFirstWordShowsWhileTheRestIsStillComing() {
        val first = event("Hello")
        // The first event arrives at once; the rest only after 3 seconds.
        reply = {
            MockResponse().setHeader("Content-Type", "text/event-stream")
                .setBody(first + event(" world") + "data: [DONE]\n\n")
                .throttleBody(first.toByteArray().size.toLong(), 3, TimeUnit.SECONDS)
        }
        val r = repo(settings())
        val sentAt = System.currentTimeMillis()
        onMain { r.send("hey") }
        val shownAfter = runBlocking {
            withTimeout(2_500) {
                withContext(main) {
                    while (r.messages.value.lastOrNull()?.text != "Hello") delay(10)
                }
            }
            System.currentTimeMillis() - sentAt
        }
        val shown = r.messages.value.last()
        assertTrue("still streaming", shown.streaming)
        assertTrue("first word took $shownAfter ms", shownAfter < 2_500)
        idle(r)
        assertEquals("Hello world", r.messages.value.last().text)
    }
}

package app.hush.music.spotiflac

import android.app.Application
import android.content.SharedPreferences
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for SpotiFLACClient 401/403 handling.
 *
 * Focuses on verifying that handleRelayError triggers clearSession,
 * and that the client methods propagate errors correctly.
 * The full network round-trip is tested via SpotiFLACSessionManagerTest.
 */
class SpotiFLACClientTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun okJson(body: String) = MockEngine { respond(body, HttpStatusCode.OK, headersOf("Content-Type" to listOf("application/json"))) }

    private fun createClientWithSession(
        sessionId: String = "test-session",
        sessionSecret: String = "test-secret",
        expiresAt: Long = System.currentTimeMillis() + 3_600_000,
        engine: MockEngine,
    ): Pair<SpotiFLACClient, SpotiFLACSessionManager> {
        val app = object : Application() {
            override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = TestPrefs()
        }
        val httpClient = HttpClient(engine) { install(ContentNegotiation) { json(this@SpotiFLACClientTest.json) } }
        val sm = SpotiFLACSessionManager(app, httpClient)
        SpotiFLACSessionManager::class.java.getDeclaredField("currentSession").apply {
            isAccessible = true; set(sm, SpotiFLACSession(sessionId, sessionSecret, expiresAt))
        }
        val client = SpotiFLACClient(httpClient, sm)
        return client to sm
    }

    // ── resolveTrack 401/403 ───────────────────────────────────────────

    @Test
    fun `resolveTrack returns failure on 401`() = runTest {
        val (client, _) = createClientWithSession(engine = MockEngine { respondError(HttpStatusCode.Unauthorized, "Unauthorized") })
        assertTrue(client.resolveTrack("track-123").isFailure)
    }

    @Test
    fun `resolveTrack returns failure on 403`() = runTest {
        val (client, _) = createClientWithSession(engine = MockEngine { respondError(HttpStatusCode.Forbidden, "Forbidden") })
        assertTrue(client.resolveTrack("track-123").isFailure)
    }

    @Test
    fun `resolveTrack returns failure on session invalid`() = runTest {
        val (client, _) = createClientWithSession(engine = MockEngine { respondError(HttpStatusCode.Unauthorized, """{"code":"SESSION_INVALID"}""") })
        assertTrue(client.resolveTrack("track-123").isFailure)
    }

    @Test
    fun `resolveTrack returns success on 200`() = runTest {
        val (client, _) = createClientWithSession(engine = okJson("""{"url":"https://stream.example.com/f.flac","title":"T","artist":"A"}"""))
        val result = client.resolveTrack("track-123")
        assertTrue(result.isSuccess)
        assertNotNull(result.getOrNull()?.url)
    }

    // ── resolveByISRC 401/403 ──────────────────────────────────────────

    @Test
    fun `resolveByISRC returns failure on 401`() = runTest {
        val (client, _) = createClientWithSession(engine = MockEngine { respondError(HttpStatusCode.Unauthorized, "Unauthorized") })
        assertTrue(client.resolveByISRC("USRC12345678").isFailure)
    }

    @Test
    fun `resolveByISRC returns failure on 403`() = runTest {
        val (client, _) = createClientWithSession(engine = MockEngine { respondError(HttpStatusCode.Forbidden, "Forbidden") })
        assertTrue(client.resolveByISRC("USRC12345678").isFailure)
    }

    // ── search 401/403 ─────────────────────────────────────────────────

    @Test
    fun `search returns failure on 401`() = runTest {
        val (client, _) = createClientWithSession(engine = MockEngine { respondError(HttpStatusCode.Unauthorized, "Unauthorized") })
        assertTrue(client.search("query").isFailure)
    }

    @Test
    fun `search returns failure on 403`() = runTest {
        val (client, _) = createClientWithSession(engine = MockEngine { respondError(HttpStatusCode.Forbidden, "Forbidden") })
        assertTrue(client.search("query").isFailure)
    }

    @Test
    fun `search returns results on 200`() = runTest {
        val (client, _) = createClientWithSession(engine = okJson("""[{"title":"Bohemian Rhapsody","artist":"Queen","id":"t1"}]"""))
        val result = client.search("bohemian rhapsody")
        assertTrue(result.isSuccess)
        assertEquals(1, result.getOrNull()?.size)
    }

    // ── handleRelayError clears session ─────────────────────────────────

    @Test
    fun `401 clears session via handleRelayError`() = runTest {
        val (client, sm) = createClientWithSession(engine = MockEngine { respondError(HttpStatusCode.Unauthorized, """{"code":"SESSION_INVALID"}""") })
        assertNotNull(sm.currentSession)
        client.resolveTrack("track-123")
        // The session should be cleared by handleRelayError → clearSession
        // Note: this tests the clearSession call; the full round-trip is in SpotiFLACSessionManagerTest
        assertNull(sm.currentSession)
    }

    @Test
    fun `403 clears session via handleRelayError`() = runTest {
        val (client, sm) = createClientWithSession(engine = MockEngine { respondError(HttpStatusCode.Forbidden, """{"code":"REQUEST_AUTH_INVALID"}""") })
        assertNotNull(sm.currentSession)
        client.resolveTrack("track-123")
        assertNull(sm.currentSession)
    }

    @Test
    fun `401 with plain body clears session`() = runTest {
        val (client, sm) = createClientWithSession(engine = MockEngine { respondError(HttpStatusCode.Unauthorized, "Unauthorized") })
        assertNotNull(sm.currentSession)
        client.resolveTrack("track-123")
        assertNull(sm.currentSession)
    }

    // ── testSource ─────────────────────────────────────────────────────

    @Test
    fun `testSource bootstraps when no session`() = runTest {
        val bootResp = """{"session_id":"new-s","session_secret":"new-sec","expires_at":"${java.time.Instant.now().plusSeconds(3600)}"}"""
        val searchResp = """[{"title":"Bohemian Rhapsody","artist":"Queen","id":"t1"}]"""
        var calls = 0
        val engine = MockEngine {
            calls++
            if (calls == 1) respond(bootResp, HttpStatusCode.OK, headersOf("Content-Type" to listOf("application/json")))
            else respond(searchResp, HttpStatusCode.OK, headersOf("Content-Type" to listOf("application/json")))
        }
        val app = object : Application() {
            override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = TestPrefs()
        }
        val httpClient = HttpClient(engine) { install(ContentNegotiation) { json(this@SpotiFLACClientTest.json) } }
        val sm = SpotiFLACSessionManager(app, httpClient)
        val client = SpotiFLACClient(httpClient, sm)
        val result = client.testSource("tidal")
        assertTrue(result.isSuccess)
        assertEquals(1, result.getOrNull()?.size)
    }

    @Test
    fun `testSource fails on CHALLENGE_PENDING`() = runTest {
        val engine = okJson("""{"challenge_id":"ch-123","turnstile_site_key":"0x4AAAA"}""")
        val app = object : Application() {
            override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = TestPrefs()
        }
        val httpClient = HttpClient(engine) { install(ContentNegotiation) { json(this@SpotiFLACClientTest.json) } }
        val sm = SpotiFLACSessionManager(app, httpClient)
        val client = SpotiFLACClient(httpClient, sm)
        val result = client.testSource("tidal")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("verification") == true)
    }

    // ── Test prefs ─────────────────────────────────────────────────────

    private class TestPrefs : SharedPreferences {
        private val store = mutableMapOf<String, Any?>()
        override fun getAll(): MutableMap<String, *> = store
        override fun getString(key: String?, defValue: String?): String? = (store[key] as? String) ?: defValue
        override fun getInt(key: String?, defValue: Int): Int = (store[key] as? Int) ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = (store[key] as? Long) ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = (store[key] as? Float) ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = (store[key] as? Boolean) ?: defValue
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = (store[key] as? MutableSet<String>) ?: defValues
        override fun contains(key: String?): Boolean = store.containsKey(key)
        override fun edit(): SharedPreferences.Editor = Editor(store)
        override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        private class Editor(private val map: MutableMap<String, Any?>) : SharedPreferences.Editor {
            private val pending = mutableMapOf<String, Any?>()
            private val removes = mutableListOf<String>()
            override fun putString(key: String?, value: String?) = apply { pending[key!!] = value }
            override fun putInt(key: String?, value: Int) = apply { pending[key!!] = value }
            override fun putLong(key: String?, value: Long) = apply { pending[key!!] = value }
            override fun putFloat(key: String?, value: Float) = apply { pending[key!!] = value }
            override fun putBoolean(key: String?, value: Boolean) = apply { pending[key!!] = value }
            override fun putStringSet(key: String?, values: MutableSet<String>?) = apply { pending[key!!] = values }
            override fun remove(key: String?) = apply { removes.add(key!!) }
            override fun clear() = apply { removes.addAll(map.keys) }
            override fun commit(): Boolean { apply(); return true }
            override fun apply() { removes.forEach { map.remove(it) }; pending.forEach { (k, v) -> map[k] = v }; pending.clear(); removes.clear() }
        }
    }
}

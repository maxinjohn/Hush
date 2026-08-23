@file:Suppress("UNCHECKED_CAST")
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SpotiFLACSessionManagerTest {

    private lateinit var fakePrefs: FakeSharedPreferences
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Before
    fun setUp() { fakePrefs = FakeSharedPreferences() }

    private fun createManager(engine: MockEngine): SpotiFLACSessionManager {
        val httpClient = HttpClient(engine) { install(ContentNegotiation) { json(this@SpotiFLACSessionManagerTest.json) } }
        val app = object : Application() {
            override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = fakePrefs
        }
        val manager = SpotiFLACSessionManager(app, httpClient)
        return manager
    }

    private fun createManagerWithSession(
        sessionId: String = "test-session-id",
        sessionSecret: String = "test-session-secret",
        expiresAt: Long = System.currentTimeMillis() + 3_600_000,
    ): SpotiFLACSessionManager {
        fakePrefs.store["session_id"] = sessionId
        fakePrefs.store["session_secret"] = sessionSecret
        fakePrefs.store["session_expires"] = expiresAt
        return createManager(MockEngine { respond("{}", HttpStatusCode.OK, headersOf("Content-Type" to listOf("application/json"))) })
    }

    private fun setSessionOnManager(manager: SpotiFLACSessionManager, session: SpotiFLACSession) {
        SpotiFLACSessionManager::class.java.getDeclaredField("currentSession").apply {
            isAccessible = true; set(manager, session)
        }
    }

    private fun jsonRespond(body: String) = MockEngine { respond(body, HttpStatusCode.OK, headersOf("Content-Type" to listOf("application/json"))) }

    // ── Session lifecycle ──────────────────────────────────────────────

    @Test
    fun `clearSession removes all stored data`() {
        val manager = createManagerWithSession()
        assertNotNull(manager.currentSession)
        manager.clearSession()
        assertNull(manager.currentSession)
        assertFalse(fakePrefs.store.containsKey("session_id"))
    }

    @Test
    fun `hasActiveSession returns false when no session`() {
        val manager = createManager(jsonRespond("{}"))
        assertFalse(manager.hasActiveSession())
    }

    @Test
    fun `hasActiveSession returns true for valid session`() {
        val manager = createManagerWithSession(expiresAt = System.currentTimeMillis() + 3_600_000)
        assertTrue(manager.hasActiveSession())
    }

    @Test
    fun `hasActiveSession returns false for expired session`() {
        val manager = createManager(jsonRespond("{}"))
        setSessionOnManager(manager, SpotiFLACSession("test", "secret", System.currentTimeMillis() - 1_000))
        assertFalse(manager.hasActiveSession())
    }

    @Test
    fun `sessionState is NONE when no session`() {
        val manager = createManager(jsonRespond("{}"))
        assertEquals(SessionState.NONE, manager.sessionState)
    }

    @Test
    fun `sessionState is ACTIVE for valid session`() {
        val manager = createManagerWithSession(expiresAt = System.currentTimeMillis() + 3_600_000)
        assertEquals(SessionState.ACTIVE, manager.sessionState)
    }

    @Test
    fun `sessionState is EXPIRED for expired session`() {
        val manager = createManager(jsonRespond("{}"))
        setSessionOnManager(manager, SpotiFLACSession("test", "secret", System.currentTimeMillis() - 1_000))
        assertEquals(SessionState.EXPIRED, manager.sessionState)
    }

    @Test
    fun `forceRestoreSession returns false when prefs are empty`() {
        val manager = createManager(jsonRespond("{}"))
        assertFalse(manager.forceRestoreSession())
    }

    // ── Bootstrap ──────────────────────────────────────────────────────

    @Test
    fun `bootstrap returns ACTIVE when server provides session`() = runTest {
        val body = """{"session_id":"new-s","session_secret":"new-sec","expires_at":"${java.time.Instant.now().plusSeconds(3600)}"}"""
        val manager = createManager(jsonRespond(body))
        val result = manager.bootstrap()
        assertTrue(result.isSuccess)
        assertEquals(SessionState.ACTIVE, result.getOrNull())
        assertEquals("new-s", manager.currentSession?.sessionId)
    }

    @Test
    fun `bootstrap returns NONE on 404`() = runTest {
        val manager = createManager(MockEngine { respondError(HttpStatusCode.NotFound, "Not found") })
        val result = manager.bootstrap()
        assertTrue(result.isSuccess)
        assertEquals(SessionState.NONE, result.getOrNull())
        assertNull(manager.currentSession)
    }

    @Test
    fun `bootstrap clears session on 401`() = runTest {
        val manager = createManagerWithSession()
        assertNotNull(manager.currentSession)
        val mgr2 = createManager(MockEngine { respondError(HttpStatusCode.Unauthorized, "Unauthorized") })
        setSessionOnManager(mgr2, manager.currentSession!!)
        val result = mgr2.bootstrap()
        assertTrue(result.isSuccess)
        assertEquals(SessionState.NONE, result.getOrNull())
        assertNull(mgr2.currentSession)
    }

    @Test
    fun `bootstrap clears session on 403`() = runTest {
        val manager = createManagerWithSession()
        val mgr2 = createManager(MockEngine { respondError(HttpStatusCode.Forbidden, "Forbidden") })
        setSessionOnManager(mgr2, manager.currentSession!!)
        val result = mgr2.bootstrap()
        assertTrue(result.isSuccess)
        assertEquals(SessionState.NONE, result.getOrNull())
        assertNull(mgr2.currentSession)
    }

    @Test
    fun `bootstrap returns NONE on server error response`() = runTest {
        val manager = createManager(jsonRespond("""{"error":"internal_failure"}"""))
        val result = manager.bootstrap()
        assertTrue(result.isSuccess)
        assertEquals(SessionState.NONE, result.getOrNull())
    }

    @Test
    fun `bootstrap returns CHALLENGE_PENDING when challenge required`() = runTest {
        val body = """{"challenge_id":"ch-123","turnstile_site_key":"0x4AAAA","server_nonce":"nonce-abc"}"""
        val manager = createManager(jsonRespond(body))
        val result = manager.bootstrap()
        assertTrue(result.isSuccess)
        assertEquals(SessionState.CHALLENGE_PENDING, result.getOrNull())
        assertEquals("ch-123", manager.challengeId)
        assertEquals("0x4AAAA", manager.turnstileSiteKey)
    }

    @Test
    fun `bootstrap returns NONE when neither session nor challenge`() = runTest {
        val manager = createManager(jsonRespond("""{"status":"unknown"}"""))
        val result = manager.bootstrap()
        assertTrue(result.isSuccess)
        assertEquals(SessionState.NONE, result.getOrNull())
    }

    @Test
    fun `bootstrap fails on Cloudflare response`() = runTest {
        val manager = createManager(MockEngine { respond("<html>Just a moment...</html>", HttpStatusCode.OK, headersOf("Content-Type" to listOf("text/html"))) })
        val result = manager.bootstrap()
        assertTrue(result.isFailure)
    }

    // ── Exchange grant ─────────────────────────────────────────────────

    @Test
    fun `exchangeGrant returns ACTIVE on success`() = runTest {
        val body = """{"session_id":"ex-s","session_secret":"ex-sec","expires_at":"${java.time.Instant.now().plusSeconds(7200)}"}"""
        val manager = createManager(jsonRespond(body))
        val result = manager.exchangeGrant("valid-grant")
        assertTrue(result.isSuccess)
        assertEquals(SessionState.ACTIVE, result.getOrNull())
        assertEquals("ex-s", manager.currentSession?.sessionId)
    }

    @Test
    fun `exchangeGrant clears session on 401`() = runTest {
        val manager = createManagerWithSession()
        val mgr2 = createManager(MockEngine { respondError(HttpStatusCode.Unauthorized, "Unauthorized") })
        setSessionOnManager(mgr2, manager.currentSession!!)
        val result = mgr2.exchangeGrant("bad")
        assertTrue(result.isFailure)
        assertNull(mgr2.currentSession)
    }

    @Test
    fun `exchangeGrant clears session on 403`() = runTest {
        val manager = createManagerWithSession()
        val mgr2 = createManager(MockEngine { respondError(HttpStatusCode.Forbidden, "Forbidden") })
        setSessionOnManager(mgr2, manager.currentSession!!)
        val result = mgr2.exchangeGrant("bad")
        assertTrue(result.isFailure)
        assertNull(mgr2.currentSession)
    }

    @Test
    fun `exchangeGrant fails on error response`() = runTest {
        val manager = createManager(jsonRespond("""{"error":"invalid_grant"}"""))
        val result = manager.exchangeGrant("bad")
        assertTrue(result.isFailure)
        assertNull(manager.currentSession)
    }

    @Test
    fun `exchangeGrant fails when no credentials`() = runTest {
        val manager = createManager(jsonRespond("""{"session_id":null,"session_secret":null}"""))
        val result = manager.exchangeGrant("bad")
        assertTrue(result.isFailure)
    }

    @Test
    fun `exchangeGrant fails on Cloudflare`() = runTest {
        val manager = createManager(MockEngine { respond("<html>moment</html>", HttpStatusCode.OK, headersOf("Content-Type" to listOf("text/html"))) })
        val result = manager.exchangeGrant("g")
        assertTrue(result.isFailure)
    }

    // ── getSignedHeaders ───────────────────────────────────────────────

    @Test
    fun `getSignedHeaders throws when no session`() {
        val manager = createManager(jsonRespond("{}"))
        try { manager.getSignedHeaders("GET", "/test"); throw AssertionError("Expected exception") }
        catch (e: SpotiFLACException) { assertTrue(e.message?.contains("No active") == true) }
    }

    @Test
    fun `getSignedHeaders clears expired session and throws`() {
        val manager = createManager(jsonRespond("{}"))
        setSessionOnManager(manager, SpotiFLACSession("test", "secret", System.currentTimeMillis() - 5_000))
        assertNotNull(manager.currentSession)
        try { manager.getSignedHeaders("GET", "/test"); throw AssertionError("Expected exception") }
        catch (e: SpotiFLACException) { assertTrue(e.message?.contains("expired") == true) }
        assertNull(manager.currentSession)
    }

    @Test
    fun `getSignedHeaders returns headers for valid session`() {
        val manager = createManager(jsonRespond("{}"))
        val validSession = SpotiFLACSession("test-id", "test-secret-${"a".repeat(20)}", System.currentTimeMillis() + 3_600_000)
        setSessionOnManager(manager, validSession)
        val headers = manager.getSignedHeaders("GET", "/test/path")
        assertTrue(headers.isNotEmpty())
        assertTrue(headers.containsKey("X-Zarz-Session"))
        assertTrue(headers.containsKey("X-Zarz-Signature"))
        assertEquals(validSession.sessionId, headers["X-Zarz-Session"])
    }

    // ── Fake SharedPreferences ─────────────────────────────────────────

    private class FakeSharedPreferences : SharedPreferences {
        internal val store = mutableMapOf<String, Any?>()
        override fun getAll(): MutableMap<String, *> = store
        override fun getString(key: String?, defValue: String?): String? = (store[key] as? String) ?: defValue
        override fun getInt(key: String?, defValue: Int): Int = (store[key] as? Int) ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = (store[key] as? Long) ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = (store[key] as? Float) ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = (store[key] as? Boolean) ?: defValue
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = (store[key] as? MutableSet<String>) ?: defValues
        override fun contains(key: String?): Boolean = store.containsKey(key)
        override fun edit(): SharedPreferences.Editor = FakeEditor(store)
        override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        private class FakeEditor(@Suppress("UNCHECKED_CAST") private val map: MutableMap<String, Any?>) : SharedPreferences.Editor {
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

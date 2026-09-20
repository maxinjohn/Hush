package app.hush.music.spotiflac

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress
import java.net.Proxy

/**
 * These pin the parts of "point SpotiFLAC somewhere else" that fail silently when they are wrong.
 *
 * The failure modes are invisible on the screen: a rejected relay address that quietly falls back to
 * the built-in one looks exactly like a relay that works, and a proxy described wrongly leaves the
 * user debugging the relay when the exit is what is wrong. The last case is why the credentials rule
 * is asserted here rather than trusted - a settings row that prints them is a leak, not a bug.
 */
class SpotiFLACRelaySettingsTest {

    private fun egress(
        type: Proxy.Type = Proxy.Type.HTTP,
        host: String = "proxy.home",
        port: Int = 3128,
        user: String? = null,
        password: String? = null,
    ) = RelayEgress(
        proxy = Proxy(type, InetSocketAddress.createUnresolved(host, port)),
        username = user,
        password = password,
    )

    // ------------------------------------------------------------------
    // The relay address
    // ------------------------------------------------------------------

    @Test
    fun `an https relay address is kept as written`() {
        assertEquals(
            "https://relay.example.com/v2",
            SpotiFLACRelayStore.normalizeBaseUrl("https://relay.example.com/v2"),
        )
    }

    @Test
    fun `a trailing slash is dropped so paths join cleanly`() {
        assertEquals(
            "https://relay.example.com/v2",
            SpotiFLACRelayStore.normalizeBaseUrl("https://relay.example.com/v2/"),
        )
        assertEquals(
            "https://relay.example.com",
            SpotiFLACRelayStore.normalizeBaseUrl("https://relay.example.com/"),
        )
    }

    @Test
    fun `a bare host is accepted as https`() {
        // What a person types. Defaulting it to https rather than rejecting it is the same call the
        // runtime makes for an extension's own base URL, and the alternative is telling them off for
        // omitting something the field can infer.
        assertEquals(
            "https://relay.example.com/v2",
            SpotiFLACRelayStore.normalizeBaseUrl("relay.example.com/v2"),
        )
    }

    @Test
    fun `a port survives normalisation`() {
        assertEquals(
            "https://relay.example.com:8443/v2",
            SpotiFLACRelayStore.normalizeBaseUrl("https://relay.example.com:8443/v2"),
        )
    }

    @Test
    fun `cleartext is refused`() {
        // These requests carry a session id and secret. A self-hosted relay over http would put them
        // on the wire, which is why this is refused rather than downgraded.
        assertNull(SpotiFLACRelayStore.normalizeBaseUrl("http://relay.example.com/v2"))
        assertNull(SpotiFLACRelayStore.normalizeBaseUrl("http://10.0.0.5:8080"))
    }

    @Test
    fun `an unusable address is null rather than a silent default`() {
        assertNull(SpotiFLACRelayStore.normalizeBaseUrl(null))
        assertNull(SpotiFLACRelayStore.normalizeBaseUrl("   "))
        assertNull(SpotiFLACRelayStore.normalizeBaseUrl("https://"))
        assertNull(SpotiFLACRelayStore.normalizeBaseUrl("https:///v2"))
    }

    @Test
    fun `an unusable address falls back to the built-in relay`() {
        val endpoint = SpotiFLACRelayStore.endpointOf("http://relay.example.com")
        assertEquals(SpotiFLACRelayStore.DEFAULT_BASE_URL, endpoint.baseUrl)
        assertTrue(endpoint.isDefaultBaseUrl)
        // Which the screen is responsible for saying out loud - it refuses to save an address it
        // cannot use rather than storing it and letting this fallback look like success.
    }

    // ------------------------------------------------------------------
    // The exit
    // ------------------------------------------------------------------

    @Test
    fun `the exit is Hush's proxy, and null when none is enabled`() {
        // Nothing configured in a unit test means no proxy preference, which must read as a direct
        // connection rather than as a broken client.
        val endpoint = SpotiFLACRelayStore.endpointOf(null)
        assertNull(endpoint.egress)
        assertTrue(endpoint.isDirect)
        assertEquals("Direct connection", endpoint.describe())
    }

    @Test
    fun `the exit is named with its kind so the row cannot mislead`() {
        val http = SpotiFLACRelayEndpoint("https://relay.example.com/v2", egress())
        assertEquals("HTTP proxy.home:3128", http.egress?.describe())
        assertEquals(
            "Via the proxy in Internet settings: HTTP proxy.home:3128",
            http.describe(),
        )

        // SOCKS is an acronym, and the row is about how traffic leaves the device, so the case
        // matters: "socks" reads as a setting nobody chose.
        val socks = SpotiFLACRelayEndpoint("https://x/v2", egress(type = Proxy.Type.SOCKS, port = 1080))
        assertEquals("SOCKS proxy.home:1080", socks.egress?.describe())
    }

    @Test
    fun `credentials are acknowledged but never printed`() {
        val endpoint =
            SpotiFLACRelayEndpoint(
                baseUrl = "https://relay.example.com/v2",
                egress = egress(user = "someone", password = "hunter2"),
            )
        val described = endpoint.describe() + endpoint.egress!!.describe()
        assertTrue("the exit is still named", described.contains("proxy.home:3128"))
        assertTrue("auth is acknowledged", described.contains("(with credentials)"))
        assertFalse("the secret must not be printable", described.contains("hunter2"))
        assertFalse("nor the account name", described.contains("someone"))
    }

    @Test
    fun `a relay of the user's own is named in the summary`() {
        val endpoint = SpotiFLACRelayEndpoint("https://relay.example.com/v2", egress())
        assertFalse(endpoint.isDefaultBaseUrl)
        assertFalse(endpoint.isDirect)
    }
}

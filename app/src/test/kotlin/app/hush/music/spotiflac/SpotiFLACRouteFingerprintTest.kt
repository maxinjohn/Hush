package app.hush.music.spotiflac

import androidx.datastore.preferences.core.preferencesOf
import app.hush.music.constants.ProxyEnabledKey
import app.hush.music.constants.ProxyHostKey
import app.hush.music.constants.ProxyPortKey
import app.hush.music.constants.ProxyTypeKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * These pin the rule that decides whether a gateway block still means anything.
 *
 * Both directions are expensive when they are wrong: claiming a change where there is none makes the
 * app ask a gateway that is already refusing it (which is what extends a block), and missing a real
 * change leaves playback suppressed for hours against an address the device has left. So the tests
 * are about *stability* as much as about difference.
 */
class SpotiFLACRouteFingerprintTest {

    private fun route(
        proxyEnabled: Boolean? = false,
        proxyType: String? = null,
        proxyHost: String? = null,
        proxyPort: Int? = null,
        network: String? = "wifi",
    ) = SpotiFLACRouteFingerprint.of(proxyEnabled, proxyType, proxyHost, proxyPort, network)

    @Test
    fun `the same route always renders the same way`() {
        // A fingerprint that shifted between two reads of an unchanged device would look like a route
        // change, and the app would re-ask the gateway on its own schedule.
        val first = route()
        val second = route()
        assertEquals(first, second)
    }

    @Test
    fun `a VPN coming up is a different route`() {
        // The case this was built for, measured on the reporting device: the block lifted the moment a
        // VPN was switched on, and nothing but this can see that.
        assertNotEquals(route(network = "wifi"), route(network = "vpn+wifi"))
    }

    @Test
    fun `a handover to another network is a different route`() {
        assertNotEquals(route(network = "wifi"), route(network = "cell"))
    }

    @Test
    fun `enabling a proxy is a different route, and disabling it is the old one again`() {
        val direct = route()
        val throughProxy = route(proxyEnabled = true, proxyType = "HTTP", proxyHost = "10.0.0.5", proxyPort = 3128)
        assertNotEquals(direct, throughProxy)
        // Switching it back off returns the device to the route it was blocked on, so the remembered
        // block applies again instead of being dismissed as "a different route".
        assertEquals(direct, route(proxyEnabled = false, proxyHost = "10.0.0.5", proxyPort = 3128))
    }

    @Test
    fun `a proxy host typed while the proxy is off is not a route change`() {
        // Half-configured settings are not a route: the traffic leaves directly either way, and treating
        // this as a change would make the settings screen itself provoke a request.
        assertEquals(route(), route(proxyHost = "relay.example.com", proxyPort = 8443))
    }

    @Test
    fun `completing a proxy is a route change`() {
        val halfTyped = route(proxyEnabled = true, proxyType = "HTTP", proxyHost = "10.0.0.5")
        val completed = route(proxyEnabled = true, proxyType = "HTTP", proxyHost = "10.0.0.5", proxyPort = 3128)
        assertNotEquals(halfTyped, completed)
    }

    @Test
    fun `an unreadable network does not make the route look different`() {
        // "unknown" is what the fingerprint says when the platform will not answer; two such reads must
        // agree with each other, or a device that cannot report its transport would never stop asking.
        assertEquals(route(network = null), route(network = "   "))
    }

    @Test
    fun `a stored block is kept while the route is unchanged`() {
        val current = route(network = "vpn+wifi")
        assertTrue(SpotiFLACRouteFingerprint.stillDescribes(current, current))
    }

    @Test
    fun `a stored block is dropped when the route has moved`() {
        assertFalse(
            SpotiFLACRouteFingerprint.stillDescribes(
                recorded = route(network = "wifi"),
                current = route(network = "vpn+wifi"),
            ),
        )
    }

    @Test
    fun `a block with no recorded route is never dismissed as stale`() {
        assertTrue(SpotiFLACRouteFingerprint.stillDescribes(null, route()))
        assertTrue(SpotiFLACRouteFingerprint.stillDescribes("", route()))
    }

    @Test
    fun `two networks the durable route cannot tell apart are still a move to the live one`() {
        // The case that reported itself as "I switched networks and nothing happened". Both Wi-Fi
        // networks render the *same* durable route - `exit=direct net=wifi` - so a watch comparing that
        // alone returned early and never asked. It is not the durable half that is wrong (a network
        // handle cannot be trusted across restarts for *asking*: a record built from one would make
        // every launch a fresh request at a blocking gateway); it is that a move *inside one run of the
        // app* needs the half that does not have to survive a restart.
        val sameRoute = route(network = "wifi")
        val atHome = SpotiFLACRouteFingerprint.liveAddressOf(sameRoute, "Network{100}|192.168.1.24")
        val inTheCar = SpotiFLACRouteFingerprint.liveAddressOf(sameRoute, "Network{101}|192.168.8.3")
        assertNotEquals(atHome, inTheCar)
        assertTrue(SpotiFLACRouteFingerprint.addressChanged(recorded = atHome, current = inTheCar))
    }

    @Test
    fun `the live route is stable while the network is`() {
        // The other direction, and the one a throttled probe depends on: reading the same network twice
        // must produce the same answer, or the watch would treat its own idle reading as a move.
        val address = SpotiFLACRouteFingerprint.liveAddressOf(route(network = "wifi"), "Network{100}|192.168.1.24")
        assertEquals(
            SpotiFLACRouteFingerprint.liveOf(address, validated = true),
            SpotiFLACRouteFingerprint.liveOf(address, validated = true),
        )
        assertFalse(SpotiFLACRouteFingerprint.addressChanged(recorded = address, current = address))
    }

    @Test
    fun `the live route carries the durable one, so a proxy change moves it too`() {
        val identity = "Network{100}|192.168.1.24"
        assertNotEquals(
            SpotiFLACRouteFingerprint.liveAddressOf(route(), identity),
            SpotiFLACRouteFingerprint.liveAddressOf(
                route(proxyEnabled = true, proxyType = "HTTP", proxyHost = "10.0.0.5", proxyPort = 3128),
                identity,
            ),
        )
    }

    @Test
    fun `a network that has not finished validating is a different live route, but not another address`() {
        // The two halves answer two questions, and this is where they part company. Still coming up is a
        // reason to *ask* the gateway again - the request made then cannot reach anything, so nothing
        // else would. It is not another address, so it must not retire what the gateway said: a network
        // throwing its validated flag while it reconnects is the same address, and reading it as a move
        // would throw away a block that is still true.
        val address = SpotiFLACRouteFingerprint.liveAddressOf(route(network = "wifi"), "Network{100}|192.168.1.24")
        assertNotEquals(
            SpotiFLACRouteFingerprint.liveOf(address, validated = false),
            SpotiFLACRouteFingerprint.liveOf(address, validated = true),
        )
        assertFalse(SpotiFLACRouteFingerprint.addressChanged(recorded = address, current = address))
    }

    @Test
    fun `a block with no recorded address is never retired by one`() {
        // A record written before addresses were kept: treating unknown provenance as a move is how a
        // client ends up asking a blocking gateway on every launch.
        val address = SpotiFLACRouteFingerprint.liveAddressOf(route(), "Network{100}|192.168.1.24")
        assertFalse(SpotiFLACRouteFingerprint.addressChanged(recorded = null, current = address))
        assertFalse(SpotiFLACRouteFingerprint.addressChanged(recorded = "", current = address))
    }

    @Test
    fun `the proxy half of a stored preference set matches the live route it describes`() {
        // The watch compares preference snapshots against a live fingerprint, so the proxy half has to
        // render identically on both sides - otherwise every single preference emission looks like a
        // change and the gateway is asked on each one.
        val prefs =
            preferencesOf(
                ProxyEnabledKey to true,
                ProxyTypeKey to "HTTP",
                ProxyHostKey to "10.0.0.5",
                ProxyPortKey to 3128,
            )
        assertEquals(
            SpotiFLACRouteFingerprint.of(true, "HTTP", "10.0.0.5", 3128, null),
            SpotiFLACRouteFingerprint.ofPreferences(prefs),
        )
        // And a preference set with no proxy at all is the direct exit.
        assertEquals(
            SpotiFLACRouteFingerprint.of(false, null, null, null, null),
            SpotiFLACRouteFingerprint.ofPreferences(preferencesOf()),
        )
    }
}

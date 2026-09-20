package app.hush.music.spotiflac

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules that decide when the app is allowed to ask the gateway about a route, and what an answer
 * means once it has.
 *
 * Both halves of this were wrong in the same direction: the app knew less about the route than the
 * gateway did, and it said nothing rather than saying something wrong - so a device that moved onto a
 * network the gateway refuses had no block recorded, and its first play walked the whole provider
 * chain before reaching YouTube. That is measured: 96s for a six-source chain.
 */
class SpotiFLACRouteProbeTest {

    private val wifiA = "exit=direct net=wifi id=Network{100}|192.168.1.24|validated=true"
    private val wifiB = "exit=direct net=wifi id=Network{101}|192.168.8.3|validated=true"

    @Test
    fun `a different route is not held back by the interval the old one spent`() {
        // The case that was suppressed: switching from one Wi-Fi network to another well inside the
        // minute a failed sweep had already spent. Keyed on time alone, the move onto the blocked network
        // was swallowed by the interval and the block the gateway would have announced was never
        // recorded - so the next play walked the whole provider chain on a route the gateway had already
        // said no to. The settle floor still applies (that is what the last case here is about), but the
        // minute does not.
        val justSettled = SpotiFLACSessionRenewer.PROBE_SETTLE_MS
        assertTrue(SpotiFLACSessionRenewer.probeIsDue(wifiB, lastRoute = wifiA, sinceLastMs = justSettled))
        assertTrue(SpotiFLACSessionRenewer.probeIsDue(wifiB, lastRoute = wifiA, sinceLastMs = 60_000L))
    }

    @Test
    fun `the same route is not asked about twice in the interval`() {
        // What the interval is for: asking a blocking gateway again is what extends a block, so a burst
        // of failures on one route has to cost one request rather than one per failure.
        assertFalse(SpotiFLACSessionRenewer.probeIsDue(wifiA, lastRoute = wifiA, sinceLastMs = 30_000L))
    }

    @Test
    fun `the same route is asked about again once the interval has passed`() {
        assertTrue(SpotiFLACSessionRenewer.probeIsDue(wifiA, lastRoute = wifiA, sinceLastMs = 61_000L))
    }

    @Test
    fun `the first question about a route is never throttled by the interval`() {
        assertTrue(SpotiFLACSessionRenewer.probeIsDue(wifiA, lastRoute = null, sinceLastMs = 61_000L))
    }

    @Test
    fun `nothing is asked in the same instant as a previous probe`() {
        // One network change arrives as several events - the old network going away, the new one
        // arriving, its capabilities changing - and the address is still being assigned while the first
        // of them is delivered. Without this floor the app would ask a blocking gateway several times
        // about an address that had not settled yet.
        assertFalse(SpotiFLACSessionRenewer.probeIsDue(wifiB, lastRoute = wifiA, sinceLastMs = 0L))
        assertFalse(SpotiFLACSessionRenewer.probeIsDue(wifiB, lastRoute = wifiA, sinceLastMs = 1_000L))
        assertFalse(
            SpotiFLACSessionRenewer.probeIsDue(
                wifiB,
                lastRoute = wifiA,
                sinceLastMs = SpotiFLACSessionRenewer.PROBE_SETTLE_MS - 1,
            ),
        )
        assertTrue(
            SpotiFLACSessionRenewer.probeIsDue(
                wifiB,
                lastRoute = wifiA,
                sinceLastMs = SpotiFLACSessionRenewer.PROBE_SETTLE_MS,
            ),
        )
    }

    @Test
    fun `a route the gateway answered without refusing is served`() {
        val probe = SpotiFLACSessionRenewer.RouteProbe(block = null, asked = true, answering = true)
        assertTrue(probe.served)
    }

    @Test
    fun `a probe that was not made does not make a route look served`() {
        val probe = SpotiFLACSessionRenewer.RouteProbe(block = null, asked = false, answering = false)
        assertFalse(probe.served)
    }

    @Test
    fun `a request that never reached the gateway does not make a route look served`() {
        // The state a route change arrives in most often, because the network is still coming up. It used
        // to be read as "served", which cleared every refusal the previous route had earned and recorded
        // nothing about this one - so the block went unstated and the next play paid for it.
        val probe = SpotiFLACSessionRenewer.RouteProbe(block = null, asked = true, answering = false)
        assertFalse(probe.served)
    }

    @Test
    fun `a route the gateway refused is not served, whatever else it said`() {
        val block =
            SpotiFLACSessionRenewer.RelayBlock(
                untilMs = 10_000L,
                reason = "gateway is refusing this connection (HTTP 429)",
                remainingMs = 9_000L,
            )
        assertFalse(
            SpotiFLACSessionRenewer.RouteProbe(block = block, asked = true, answering = true).served,
        )
        assertFalse(
            SpotiFLACSessionRenewer.RouteProbe(block = block, asked = true, answering = false).served,
        )
    }
}

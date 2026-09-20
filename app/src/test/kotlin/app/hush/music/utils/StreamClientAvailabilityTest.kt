/*
 * Hush (2026)
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 and Section 5
 */

package app.hush.music.utils

import app.hush.music.constants.PlayerStreamClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The sweep used to lead with a client that could not answer, on every resolve.
 *
 * On the reporting device WEB_REMIX was led with on every track - its formats arrived ciphered and the
 * decipher failed every single time (`Could not find deobfuscation function`) - while ANDROID_VR
 * answered with direct URLs that needed no deciphering at all. The same device also had five client
 * families refused for the *address* it was on (`LOGIN_REQUIRED`, "Sign in to confirm you're not a
 * bot"). Both are remembered here; deferring what cannot answer is the difference between playing on
 * the first request and paying for eight that fail.
 */
class StreamClientAvailabilityTest {

    @Before
    @After
    fun resetMarks() {
        StreamClientAvailability.forgetAll()
    }

    @Test
    fun `a client that cannot answer is deferred rather than dropped`() {
        val ordered =
            StreamClientAvailability.preferAnswering(
                clients = listOf("WEB_REMIX", "ANDROID_VR", "VISIONOS"),
                unavailable = setOf("WEB_REMIX"),
                key = { it },
            )

        assertEquals(listOf("ANDROID_VR", "VISIONOS", "WEB_REMIX"), ordered)
    }

    @Test
    fun `everything that can answer keeps the order the sweep composed`() {
        val clients = listOf("ANDROID_VR", "IOS", "VISIONOS", "TVHTML5")
        assertEquals(
            clients,
            StreamClientAvailability.preferAnswering(clients, unavailable = emptySet()) { it },
        )
    }

    @Test
    fun `deferred clients keep their own relative order`() {
        val ordered =
            StreamClientAvailability.preferAnswering(
                clients = listOf("WEB_CREATOR", "ANDROID_VR", "WEB_REMIX"),
                unavailable = setOf("WEB_CREATOR", "WEB_REMIX"),
                key = { it },
            )
        assertEquals(listOf("ANDROID_VR", "WEB_CREATOR", "WEB_REMIX"), ordered)
    }

    @Test
    fun `a sweep whose only answers are deferred still asks them`() {
        val ordered =
            StreamClientAvailability.preferAnswering(
                clients = listOf("WEB_REMIX", "ANDROID_MUSIC"),
                unavailable = setOf("WEB_REMIX", "ANDROID_MUSIC"),
                key = { it },
            )
        assertEquals(listOf("WEB_REMIX", "ANDROID_MUSIC"), ordered)
    }

    @Test
    fun `a mark covers the whole family, not one version string`() {
        StreamClientAvailability.markCannotDecipher("ANDROID_VR", nowMs = 1_000L)

        assertTrue(StreamClientAvailability.cannotDecipher("ANDROID_VR_1_65_10", nowMs = 2_000L))
        assertTrue(StreamClientAvailability.cannotDecipher("ANDROID_VR", nowMs = 2_000L))
        assertFalse(StreamClientAvailability.cannotDecipher("VISIONOS", nowMs = 2_000L))
    }

    @Test
    fun `a decipher mark expires instead of condemning the client forever`() {
        StreamClientAvailability.markCannotDecipher("WEB_REMIX", nowMs = 1_000L)

        assertTrue(
            StreamClientAvailability.cannotDecipher(
                "WEB_REMIX",
                nowMs = 1_000L + StreamClientAvailability.CANNOT_DECIPHER_TTL_MS - 1,
            ),
        )
        assertFalse(
            StreamClientAvailability.cannotDecipher(
                "WEB_REMIX",
                nowMs = 1_000L + StreamClientAvailability.CANNOT_DECIPHER_TTL_MS + 1,
            ),
        )
        assertTrue(
            StreamClientAvailability
                .unavailableFamilies(nowMs = 1_000L + StreamClientAvailability.CANNOT_DECIPHER_TTL_MS + 1)
                .isEmpty(),
        )
    }

    @Test
    fun `an address refusal is remembered, and forgotten sooner than a decipher failure`() {
        StreamClientAvailability.markRefused("ANDROID_VR_1_65_10", nowMs = 1_000L)

        assertTrue(StreamClientAvailability.isRefused("ANDROID_VR_1_65_10", nowMs = 2_000L))
        assertTrue(StreamClientAvailability.cannotAnswer("ANDROID_VR", nowMs = 2_000L))
        // A refusal is about where the request came from and lifts on its own, so it must not hold a
        // client back for as long as a capability this device simply lacks.
        assertTrue(
            StreamClientAvailability.REFUSED_TTL_MS < StreamClientAvailability.CANNOT_DECIPHER_TTL_MS,
        )
        assertFalse(
            StreamClientAvailability.isRefused(
                "ANDROID_VR_1_65_10",
                nowMs = 1_000L + StreamClientAvailability.REFUSED_TTL_MS + 1,
            ),
        )
    }

    @Test
    fun `only the refused families are reported as refused`() {
        StreamClientAvailability.markRefused("IOS_MUSIC")
        StreamClientAvailability.markCannotDecipher("WEB_REMIX")

        assertEquals(setOf("IOS"), StreamClientAvailability.refusedFamilies())
        assertEquals(
            setOf("IOS", "WEB_REMIX"),
            StreamClientAvailability.unavailableFamilies(),
        )
    }

    @Test
    fun `a failing decipher is known device-wide, not per client`() {
        assertTrue(StreamClientAvailability.canDecipher(nowMs = 1_000L))

        StreamClientAvailability.markDecipherUnavailable(nowMs = 1_000L)

        // Every ciphered candidate goes through the same extractor, so once it has failed the next
        // client's ciphered formats cannot be deciphered either - asking costs a decipher attempt apiece.
        assertFalse(StreamClientAvailability.canDecipher(nowMs = 1_001L))
        assertTrue(
            StreamClientAvailability.canDecipher(
                nowMs = 1_000L + StreamClientAvailability.CANNOT_DECIPHER_TTL_MS,
            ),
        )
    }

    @Test
    fun `forgetting the marks forgets the failing decipher too`() {
        StreamClientAvailability.markDecipherUnavailable(nowMs = 1_000L)
        StreamClientAvailability.forgetAll()

        assertTrue(StreamClientAvailability.canDecipher(nowMs = 1_001L))
    }

    @Test
    fun `an empty client name is never marked`() {
        StreamClientAvailability.markCannotDecipher("   ")
        StreamClientAvailability.markRefused("")

        assertTrue(StreamClientAvailability.unavailableFamilies().isEmpty())
    }

    @Test
    fun `web remix is not preferred once its signatures are known to be undecipherable`() {
        // The promotion exists to get the web client's formats; when the decipher is failing, leading
        // with it means leading with a client that cannot produce a stream.
        assertTrue(
            YTPlayerUtils.shouldPreferWebRemixForLoggedInPlayback(
                preferredStreamClient = PlayerStreamClient.ANDROID_VR,
                isLoggedIn = true,
                webClientPoTokenEnabled = true,
                hasPlayerPoToken = true,
                hasGvsPoToken = true,
                webRemixCanDecipher = true,
            ),
        )
        assertFalse(
            YTPlayerUtils.shouldPreferWebRemixForLoggedInPlayback(
                preferredStreamClient = PlayerStreamClient.ANDROID_VR,
                isLoggedIn = true,
                webClientPoTokenEnabled = true,
                hasPlayerPoToken = true,
                hasGvsPoToken = true,
                webRemixCanDecipher = false,
            ),
        )
    }

    @Test
    fun `a client family key is what the sweep is ordered by`() {
        assertEquals("WEB_REMIX", StreamClientAvailability.keyOf("WEB_REMIX"))
        assertEquals(
            StreamClientAvailability.keyOf("ANDROID_VR"),
            StreamClientAvailability.keyOf("ANDROID_VR_1_65_10"),
        )
        assertEquals(
            StreamClientAvailability.keyOf("WEB_REMIX"),
            StreamClientAvailability.keyOf("WEB_REMIX@1.20260213.01.00"),
        )
    }

    @Test
    fun `the same family reached by a different name is still one client`() {
        // Measured on the reporting device: `IOS_MUSIC@7.27.0` was refused, then the sweep tried
        // `IOS@19.29.1` and `IOS@19.22.3` - the same family, each name a fresh client to refuse.
        val names = listOf("IOS_MUSIC@7.27.0", "IOS@19.29.1", "IOS@19.22.3", "IOS", "IPADOS")
        assertEquals(setOf("IOS"), names.map { StreamClientAvailability.keyOf(it) }.toSet())
    }

    @Test
    fun `one refusal defers the whole family, whatever version the sweep reaches next`() {
        StreamClientAvailability.markRefused("IOS_MUSIC@7.27.0")

        val ordered =
            StreamClientAvailability.preferAnswering(
                clients = listOf("IOS@19.29.1", "ANDROID_VR", "IOS@19.22.3"),
                unavailable = StreamClientAvailability.unavailableFamilies(),
                key = { StreamClientAvailability.keyOf(it) },
            )

        assertEquals(listOf("ANDROID_VR", "IOS@19.29.1", "IOS@19.22.3"), ordered)
    }
}

package app.hush.music.together

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TogetherLinkTest {
    private val sample =
        TogetherJoinInfo(
            host = "192.168.1.10",
            port = 8765,
            sessionId = "session-1",
            sessionKey = "secret-key",
        )

    @Test
    fun encodeUsesHushScheme() {
        val link = TogetherLink.encode(sample)
        assertTrue(link.startsWith("hush://together?"))
    }

    @Test
    fun decodeAcceptsHushScheme() {
        val encoded = TogetherLink.encode(sample)
        val decoded = TogetherLink.decode(encoded)
        assertNotNull(decoded)
        assertEquals(sample, decoded)
    }

    @Test
    fun decodeAcceptsLegacyArchiveTuneScheme() {
        val legacy = TogetherLink.encode(sample).replaceFirst("hush://", "archivetune://")
        val decoded = TogetherLink.decode(legacy)
        assertNotNull(decoded)
        assertEquals(sample, decoded)
    }
}

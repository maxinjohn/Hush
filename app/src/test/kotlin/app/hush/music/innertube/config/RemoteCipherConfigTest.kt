package app.hush.music.innertube.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RemoteCipherConfigTest {
    @Test
    fun `parses Zemer player entries and aliases`() {
        val config = RemoteCipherConfig.parse(
            """
            {
              "schemaVersion": 1,
              "players": {
                "abcd1234": {
                  "sig": "Tl(1,2,INPUT)",
                  "nClass": "W_",
                  "sts": 20602,
                  "aliases": ["alias123"]
                }
              }
            }
            """.trimIndent(),
        ).getOrThrow()

        assertEquals("W_", RemoteCipherConfig.playerConfig(config, "alias123")?.throttlingClass)
        assertEquals(20602, RemoteCipherConfig.effectiveSignatureTimestamp(config, "alias123", null))
    }

    @Test
    fun `prefers local timestamp when player is unknown`() {
        val config = RemoteCipherConfig.parse(
            """{"players":{"known":{"sig":"sig","nClass":"n","sts":20602}}}""",
        ).getOrThrow()

        assertEquals(12345, RemoteCipherConfig.effectiveSignatureTimestamp(config, "unknown", 12345))
        assertNull(RemoteCipherConfig.playerConfig(config, "missing"))
    }
}

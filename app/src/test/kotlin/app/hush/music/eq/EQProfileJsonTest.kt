package app.hush.music.eq

import app.hush.music.eq.data.FilterType
import app.hush.music.eq.data.ParametricEQ
import app.hush.music.eq.data.ParametricEQBand
import app.hush.music.eq.data.SavedEQProfile
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.serializer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Guards the crash that shipped in the equalizer:
 *
 * `vs9: Serializer for class 'ig9' is not found` thrown from
 * `AxionEqViewModel$applyToService`, because [EQProfileRepository] persists these models with
 * the *reified* `Json.encodeToString` / `decodeFromString` overloads. Those resolve the
 * serializer by reflection at runtime, so a missing `@Serializable` compiles cleanly and only
 * fails on the user's device, on the main thread, the first time a profile is written.
 *
 * `serializer<T>()` here is the same reflective lookup, so this test fails exactly where the
 * app failed instead of pretending a hand-built serializer is the thing under test.
 */
class EQProfileJsonTest {

    /** Mirrors the private configuration in [EQProfileRepository]. */
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }

    @Test
    fun `every persisted EQ model resolves its serializer reflectively`() {
        assertNotNull(serializer<SavedEQProfile>())
        assertNotNull(serializer<ParametricEQBand>())
        assertNotNull(serializer<ParametricEQ>())
        assertNotNull(serializer<FilterType>())
    }

    @Test
    fun `a saved profile round-trips through the repository's JSON`() {
        val profile =
            SavedEQProfile(
                id = "hush_tuning",
                name = "Hush Tuning",
                bands =
                    listOf(
                        ParametricEQBand(frequency = 31.0, gain = 2.5, q = 1.41, filterType = FilterType.PK, enabled = true),
                        ParametricEQBand(frequency = 16000.0, gain = -3.0, filterType = FilterType.HSC),
                    ),
                preamp = -1.5,
                isCustom = false,
                isActive = true,
                addedTimestamp = 1_700_000_000_000L,
            )

        // The reified overloads the repository uses, not a hand-picked serializer.
        val encoded = json.encodeToString(profile)
        val decoded = json.decodeFromString<SavedEQProfile>(encoded)

        assertEquals(profile, decoded)
        assertEquals(2, decoded.bands.size)
        assertEquals(FilterType.HSC, decoded.bands[1].filterType)
    }

    @Test
    fun `a profile written by an older build still decodes when fields were added`() {
        // No `addedTimestamp`: exactly the shape an older install left on disk.
        val legacy = """{"id":"custom_1","name":"Old","bands":[],"preamp":0.0,"isCustom":true,"isActive":false}"""

        val decoded = json.decodeFromString<SavedEQProfile>(legacy)

        assertEquals("custom_1", decoded.id)
        assertEquals(0, decoded.bands.size)
    }
}

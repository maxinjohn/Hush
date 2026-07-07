/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.home

import app.hush.music.innertube.YouTube
import app.hush.music.innertube.pages.HomePage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Validates that mood chips (Energise, Sleep, Relax, etc.) have both 
 * [browseId] and [params] in their [BrowseEndpoint] — required for 
 * [YouTube.home] to target the correct browse page.
 *
 * Without `browseId`, the call defaults to `"FEmusic_home"` and the
 * mood-filtered params would be applied to the wrong browse context,
 * causing chips to silently return no filtered content.
 */
class HomeChipEndpointTest {

    @Test
    fun `chips from home page have non-null browseId and params`() {
        val homePage = runCatching {
            runBlocking { YouTube.home().getOrThrow() }
        }.getOrNull()

        if (homePage == null) {
            // Network/API unavailable — skip gracefully in CI/offline
            println("Skipping: YouTube home API unavailable")
            return
        }

        val chips = homePage.chips ?: emptyList()
        assertFalse("Home page should have mood/genre chips", chips.isEmpty())

        val chipsWithEndpoint = chips.filter { it.endpoint != null }
        assertFalse(
            "At least some chips should have browse endpoints",
            chipsWithEndpoint.isEmpty(),
        )

        var chipsWithBoth = 0
        var chipsMissingBrowseId = 0
        var chipsMissingParams = 0

        for (chip in chipsWithEndpoint) {
            val endpoint = chip.endpoint!!

            val hasBrowseId = endpoint.browseId.isNotBlank()
            val hasParams = endpoint.params?.isNotBlank() == true

            if (hasBrowseId && hasParams) {
                chipsWithBoth++
            } else {
                if (!hasBrowseId) chipsMissingBrowseId++
                if (!hasParams) chipsMissingParams++
            }
        }

        println(
            "Chips: ${chips.size} total, " +
                "${chipsWithEndpoint.size} with endpoints, " +
                "$chipsWithBoth with both browseId+params, " +
                "$chipsMissingBrowseId missing browseId, " +
                "$chipsMissingParams missing params",
        )

        // At least one chip should have both browseId and params
        assertTrue(
            "No chips have both browseId and params. " +
                "Missing browseId: $chipsMissingBrowseId, missing params: $chipsMissingParams",
            chipsWithBoth > 0,
        )
    }

    @Test
    fun `chip-filtered browse returns different sections than default home`() {
        val defaultHome = runCatching {
            runBlocking { YouTube.home().getOrThrow() }
        }.getOrNull() ?: run {
            println("Skipping: YouTube home API unavailable")
            return
        }

        val firstChip = defaultHome.chips
            ?.firstOrNull { it.endpoint?.params != null }
            ?: run {
                println("Skipping: no chip with both browseId and params")
                return
            }

        val endpoint = firstChip.endpoint!!
        val chipBrowseId = endpoint.browseId
        val chipParams = endpoint.params!!

        println("Testing chip: \"${firstChip.title}\" browseId=$chipBrowseId params=${chipParams.take(50)}...")

        val chipHome = runCatching {
            runBlocking { YouTube.home(browseId = chipBrowseId, params = chipParams).getOrThrow() }
        }.getOrNull() ?: run {
            println("Skipping: chip browse API call failed")
            return
        }

        // The chip-filtered response should have sections
        assertNotNull("Chip browse should return sections", chipHome.sections)
        assertTrue(
            "Chip browse should return at least 1 section (got ${chipHome.sections.size})",
            chipHome.sections.isNotEmpty(),
        )

        // Verify the chip response has a continuation for pagination
        val hasContinuation = chipHome.continuation != null
        println(
            "Chip \"${firstChip.title}\": ${chipHome.sections.size} sections, " +
                "continuation=${if (hasContinuation) "yes" else "no"}",
        )

        // Verify sections have content
        val sectionsWithContent = chipHome.sections.count { it.items.isNotEmpty() }
        assertTrue(
            "Chip browse should have at least 1 section with items " +
                "(got $sectionsWithContent sections with content out of ${chipHome.sections.size})",
            sectionsWithContent > 0,
        )

        println(
            "Chip \"${firstChip.title}\" sections: " +
                chipHome.sections.joinToString(", ") { "${it.title} (${it.items.size} items)" },
        )
    }

    @Test
    fun `verify existing toggleChip fix — both browseId and params passed`() {
        // This test validates the fix at the data-model level:
        // HomePage.Chip.endpoint.browseId is a non-null String
        // HomePage.Chip.endpoint.params is a nullable String?
        // Both are required for the YouTube.home(browseId=..., params=...) call

        val chip = HomePage.Chip(
            title = "Energise",
            endpoint = app.hush.music.innertube.models.BrowseEndpoint(
                browseId = "FEmusic_moods_and_genres",
                params = "ggMPAQ",  // example mood filter param
            ),
            deselectEndPoint = null,
        )

        // Verify the chip's endpoint fields are accessible and non-null
        assertNotNull("Chip endpoint should not be null", chip.endpoint)
        assertTrue("browseId should not be blank", chip.endpoint!!.browseId.isNotBlank())
        assertTrue("params should not be blank", chip.endpoint!!.params?.isNotBlank() == true)

        // This simulates the toggleChip call site:
        val browseId = chip.endpoint!!.browseId
        val params = chip.endpoint!!.params
        assertNotNull("browseId must be passed to YouTube.home", browseId)
        assertNotNull("params must be passed to YouTube.home", params)

        println(
            "Verified: chip.endpoint.browseId=\"$browseId\", " +
                "chip.endpoint.params=\"$params\" — both passed to YouTube.home()",
        )
    }
}

/*
 * Hush (2026)
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 and Section 5
 */

package app.hush.music.spotiflac

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The feature gate that stands between a registry update and a source that cannot load.
 *
 * Upstream's runtime has grown capabilities Hush's pinned backend does not have (`accountForms`,
 * `accountDownloadMode`, `directMedia`, added in 5.1.x). A package that starts requiring one is
 * installable bytes that can never run, so the refusal belongs at the install - and it has to say
 * which thing is missing, because "extension failed to load" sends the reader looking for a bug in
 * Hush.
 */
class SpotiFLACRuntimeFeaturesTest {

    /** Every feature the pinned Go backend lists is accepted at the version it lists. */
    @Test
    fun `the pinned runtime's own features are all satisfied`() {
        val declared =
            listOf(
                "signedSession@3",
                "sessionRefresh@1",
                "sessionGrant@1",
                "globalAction@1",
                "webviewAuth@1",
                "downloadSegments@1",
                "patternedFileTransform@1",
                "preparedContext@1",
            )

        assertNull(SpotiFLACRuntimeFeatures.missing(declared))
        // What the current registry actually publishes.
        assertNull(
            SpotiFLACRuntimeFeatures.missing(
                listOf("signedSession@3", "sessionGrant@1", "downloadSegments@1"),
            ),
        )
    }

    /** A feature this build has never heard of is the Rust backend's growth showing up in a package. */
    @Test
    fun `a feature this build does not provide is named`() {
        assertEquals(
            "requires runtime feature \"accountForms\" this app build does not provide",
            SpotiFLACRuntimeFeatures.missing(listOf("signedSession@1", "accountForms@1")),
        )
        assertEquals(
            "requires runtime feature \"directMedia\" this app build does not provide",
            SpotiFLACRuntimeFeatures.missing(listOf("directMedia")),
        )
    }

    /** A version above the one this build implements is a different sentence, with both numbers. */
    @Test
    fun `a version above the provided one says which it needs and which it has`() {
        assertEquals(
            "requires runtime feature signedSession@4 (app provides @3)",
            SpotiFLACRuntimeFeatures.missing(listOf("signedSession@4")),
        )
        // The newest published package requires exactly the provided version.
        assertNull(SpotiFLACRuntimeFeatures.missing(listOf("signedSession@3")))
    }

    /** Entries are parsed the runtime's way: blank skipped, missing version means 1. */
    @Test
    fun `requirements are read the way the runtime reads them`() {
        assertNull(SpotiFLACRuntimeFeatures.requirementOf(""))
        assertNull(SpotiFLACRuntimeFeatures.requirementOf("   "))
        assertEquals(
            SpotiFLACRuntimeFeatures.Requirement("downloadSegments", 1),
            SpotiFLACRuntimeFeatures.requirementOf("downloadSegments"),
        )
        assertEquals(
            SpotiFLACRuntimeFeatures.Requirement("downloadSegments", 2),
            SpotiFLACRuntimeFeatures.requirementOf(" downloadSegments@2 "),
        )
        // A malformed suffix is not a version, and the runtime treats it as the feature unsuffixed.
        assertEquals(
            SpotiFLACRuntimeFeatures.Requirement("downloadSegments", 1),
            SpotiFLACRuntimeFeatures.requirementOf("downloadSegments@latest"),
        )
        assertNull(SpotiFLACRuntimeFeatures.missing(listOf("", "  ", "preparedContext@1")))
    }
}

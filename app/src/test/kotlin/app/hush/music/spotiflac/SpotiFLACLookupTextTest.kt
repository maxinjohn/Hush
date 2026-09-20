/*
 * Hush (2026)
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.spotiflac

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What a SpotiFLAC provider is asked to match.
 *
 * Every title and artist in here is taken from a device log where the raw text produced
 * `Invalid <service> track ID` from every provider, or from a track that already resolved, so the
 * rules are pinned against measurement rather than taste.
 */
class SpotiFLACLookupTextTest {

    @Test
    fun `a video title keeps only the song name`() {
        assertEquals(
            "Mizhiyil Mizhiyil",
            SpotiFLACLookupText.title(
                "Mizhiyil Mizhiyil | Maayabazar | Mammootty | Sheela Koul | Rahul Raj - HD Video Song | Sujatha Mohan",
            ),
        )
    }

    @Test
    fun `a cast list after a pipe never reaches the provider`() {
        assertEquals(
            "Asalayavale",
            SpotiFLACLookupText.title(
                "Asalayavale - Video Song | Khalifa | Prithviraj Sukumaran|Malvika Sharma | Jakes Bejoy x Sid Sriram",
            ),
        )
    }

    @Test
    fun `a format tag on the first segment is dropped`() {
        assertEquals("Aalilathaaliyumaay Varu Nee", SpotiFLACLookupText.title("Aalilathaaliyumaay Varu Nee - HD Video Song"))
        assertEquals("Some Song", SpotiFLACLookupText.title("Some Song (Official Video)"))
        assertEquals("Some Song", SpotiFLACLookupText.title("Some Song [4K] [Lyric Video]"))
    }

    @Test
    fun `a real variant survives cleaning, because it names a different recording`() {
        assertEquals("Wrap Me In Plastic (Slowed Down Version)", SpotiFLACLookupText.title("Wrap Me In Plastic (Slowed Down Version)"))
        assertEquals("Song Name (Live)", SpotiFLACLookupText.title("Song Name (Live)"))
        assertEquals("Song Name (Extended Mix)", SpotiFLACLookupText.title("Song Name (Extended Mix)"))
    }

    @Test
    fun `a clean title is left alone`() {
        assertEquals("Blinding Lights", SpotiFLACLookupText.title("Blinding Lights"))
        assertEquals("MAIN RAHOON YA NA RAHOON", SpotiFLACLookupText.title("MAIN RAHOON YA NA RAHOON"))
    }

    @Test
    fun `a title that is nothing but noise falls back rather than going empty`() {
        assertEquals("HD Video Song", SpotiFLACLookupText.title("HD Video Song"))
    }

    @Test
    fun `a channel is refused so the provider searches on the title alone`() {
        assertEquals("", SpotiFLACLookupText.artist("Malayalam Hits"))
        assertEquals("", SpotiFLACLookupText.artist("Music Zone"))
        assertEquals("", SpotiFLACLookupText.artist("Sony Music Malayalam"))
        assertEquals("", SpotiFLACLookupText.artist("Saregama Music"))
        assertEquals("", SpotiFLACLookupText.artist("Some Artist - Topic"))
        assertEquals("", SpotiFLACLookupText.artist("T-Series"))
        assertEquals("", SpotiFLACLookupText.artist("Official"))
    }

    @Test
    fun `a performing artist is kept`() {
        assertEquals("Ed Sheeran", SpotiFLACLookupText.artist("Ed Sheeran"))
        assertEquals("The Weeknd", SpotiFLACLookupText.artist("The Weeknd"))
        assertEquals("Jakes Bejoy", SpotiFLACLookupText.artist("Jakes Bejoy, Muthu, Mohammed Maqbool Mansoor, Sid Sriram"))
    }

    @Test
    fun `an empty artist stays empty and never becomes a name`() {
        assertEquals("", SpotiFLACLookupText.artist(""))
        assertEquals("", SpotiFLACLookupText.artist("   "))
    }
}

package moe.rukamori.archivetune.lyrics

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsLanguageFilterTest {
  @Test
  fun rejectsArabicLyricsForMalayalamSong() {
    val title = "കാതിരുന്ന്"
    val artist = "Vineeth Sreenivasan"
    val lyrics =
      """
      [00:12.00]الحب الحقيقي يبقى للأبد
      [00:18.00]في كل لحظة أنت معي
      """.trimIndent()

    assertFalse(LyricsLanguageFilter.isAcceptableLyrics(lyrics, title, artist, contentLanguage = "ml"))
  }

  @Test
  fun rejectsJapaneseLyricsForMalayalamSong() {
    val title = "മലയാളം പാട്ട്"
    val artist = "Artist"
    val lyrics =
      """
      [00:10.00]君のことが好きだよ
      [00:15.00]ずっと一緒にいたい
      """.trimIndent()

    assertFalse(LyricsLanguageFilter.isAcceptableLyrics(lyrics, title, artist, contentLanguage = "ml"))
  }

  @Test
  fun acceptsMalayalamLyricsForMalayalamSong() {
    val title = "കാതിരുന്ന്"
    val artist = "Vineeth Sreenivasan"
    val lyrics =
      """
      [00:12.00]കാതിരുന്ന നീളം നിറഞ്ഞു
      [00:18.00]എന്റെ ഹൃദയം പൂത്തു
      """.trimIndent()

    assertTrue(LyricsLanguageFilter.isAcceptableLyrics(lyrics, title, artist, contentLanguage = "ml"))
  }

  @Test
  fun acceptsEnglishLyricsForMalayalamSong() {
    val title = "കാതിരുന്ന്"
    val artist = "Vineeth Sreenivasan"
    val lyrics =
      """
      [00:12.00]Waiting for you through the night
      [00:18.00]My heart is full of light
      """.trimIndent()

    assertTrue(LyricsLanguageFilter.isAcceptableLyrics(lyrics, title, artist, contentLanguage = "ml"))
  }

  @Test
  fun rejectsIndonesianLyricsForMalayalamSong() {
    val title = "കാതിരുന്ന്"
    val artist = "Vineeth Sreenivasan"
    val lyrics =
      """
      [00:12.00]Aku menunggu kamu di sini
      [00:18.00]Dengan hati yang penuh cinta untukmu
      [00:24.00]Karena kamu adalah yang terindah
      """.trimIndent()

    assertFalse(LyricsLanguageFilter.isAcceptableLyrics(lyrics, title, artist, contentLanguage = "ml"))
  }

  @Test
  fun acceptsTamilLyricsForMalayalamSong() {
    val title = "കാതിരുന്ന്"
    val artist = "Vineeth Sreenivasan"
    val lyrics =
      """
      [00:12.00]உன்னை காத்திருந்தேன்
      [00:18.00]என் இதயம் நிறைந்தது
      """.trimIndent()

    assertTrue(LyricsLanguageFilter.isAcceptableLyrics(lyrics, title, artist, contentLanguage = "ml"))
  }

  @Test
  fun rejectsArabicLyricsForEnglishTitleIndianCountry() {
    val title = "Local Sigma"
    val artist = "Bibin Ashok"
    val lyrics =
      """
      [00:12.00]الحب الحقيقي يبقى للأبد
      [00:18.00]في كل لحظة أنت معي
      """.trimIndent()

    assertFalse(
      LyricsLanguageFilter.isAcceptableLyrics(
        lyrics,
        title,
        artist,
        contentLanguage = "en",
        contentCountry = "IN",
      ),
    )
  }
}

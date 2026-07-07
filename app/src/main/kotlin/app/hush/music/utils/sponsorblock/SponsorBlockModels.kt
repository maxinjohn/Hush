package app.hush.music.utils.sponsorblock

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class SponsorBlockSegment(
    val segment: List<Double>,
    val category: SegmentCategory = SegmentCategory.SPONSOR,
    val UUID: String = "",
    val videoDuration: Double = 0.0,
    val actionType: String = "skip",
    val locked: Int = 0,
    val votes: Int = 0,
    val description: String = "",
) {
    val start: Double get() = segment.getOrElse(0) { 0.0 }
    val end: Double get() = segment.getOrElse(1) { 0.0 }
}

@Serializable
enum class SegmentCategory {
    @SerialName("sponsor")
    SPONSOR,

    @SerialName("intro")
    INTRO,

    @SerialName("outro")
    OUTRO,

    @SerialName("selfpromo")
    SELF_PROMO,

    @SerialName("interaction")
    INTERACTION,

    @SerialName("music_offtopic")
    MUSIC_OFFTOPIC,

    @SerialName("preview")
    PREVIEW,

    @SerialName("filler")
    FILLER,

    @SerialName("exclusive_access")
    EXCLUSIVE_ACCESS,

    @SerialName("unpaid")
    UNPAID_SELF_PROMO,

    @SerialName("poi_highlight")
    POI_HIGHLIGHT,

    UNKNOWN;

    companion object {
        fun displayName(category: SegmentCategory): String = when (category) {
            SPONSOR -> "Sponsor"
            INTRO -> "Intro"
            OUTRO -> "Outro"
            SELF_PROMO -> "Self-promotion"
            INTERACTION -> "Interaction"
            MUSIC_OFFTOPIC -> "Music Offtopic"
            PREVIEW -> "Preview"
            FILLER -> "Filler"
            EXCLUSIVE_ACCESS -> "Exclusive Access"
            UNPAID_SELF_PROMO -> "Unpaid Self-promotion"
            POI_HIGHLIGHT -> "Highlight"
            UNKNOWN -> "Unknown"
        }
    }
}

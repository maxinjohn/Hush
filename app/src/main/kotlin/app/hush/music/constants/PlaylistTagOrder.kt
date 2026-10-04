/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.constants

/**
 * Reorders [availableTagIds] by the saved preference (a comma-separated id list), appending any
 * tags the user has not arranged yet so newly created tags still show up at the end.
 */
fun String.toPlaylistTagOrder(availableTagIds: List<String>): List<String> {
    val availableIds = availableTagIds.toSet()
    val savedOrder =
        split(",")
            .map(String::trim)
            .filter { it.isNotEmpty() && it in availableIds }
            .distinct()

    return savedOrder + availableTagIds.filterNot(savedOrder::contains)
}

fun List<String>.toPlaylistTagPreference(): String = distinct().joinToString(",")

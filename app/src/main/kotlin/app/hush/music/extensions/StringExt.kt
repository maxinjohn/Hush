/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.extensions

import androidx.sqlite.db.SimpleSQLiteQuery
import java.net.InetSocketAddress
import java.net.InetSocketAddress.createUnresolved
import java.text.Normalizer

inline fun <reified T : Enum<T>> String?.toEnum(defaultValue: T): T =
    if (this == null) {
        defaultValue
    } else {
        try {
            enumValueOf(this)
        } catch (e: IllegalArgumentException) {
            defaultValue
        }
    }

fun String.toSQLiteQuery(): SimpleSQLiteQuery = SimpleSQLiteQuery(this)

fun String.toInetSocketAddress(): InetSocketAddress {
    val (host, port) = split(":")
    return createUnresolved(host, port.toInt())
}

private val combiningDiacriticalMarksRegex = "\\p{Mn}+".toRegex()

/**
 * Case- and diacritics-insensitive form of this string for library search matching:
 * NFD-split so accented letters (é → e) lose their marks, then lowercased.
 */
fun String.normalizeForSearch(): String =
    Normalizer
        .normalize(this.trim(), Normalizer.Form.NFD)
        .replace(combiningDiacriticalMarksRegex, "")
        .lowercase()

/**
 * True when [normalizedQuery] — normalized here too, so any caller input works — is blank
 * or appears in any of [values]. Null values never match. Pass every field a user might
 * search by: title, album, author, artists.
 */
fun matchesNormalizedQuery(
    normalizedQuery: String,
    vararg values: String?,
): Boolean {
    val query = normalizedQuery.normalizeForSearch()
    if (query.isBlank()) return true
    return values.any { value ->
        value?.normalizeForSearch()?.contains(query) == true
    }
}

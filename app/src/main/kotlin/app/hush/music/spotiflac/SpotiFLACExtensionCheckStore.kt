/*
 * Hush (2026)
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.spotiflac

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What the last check of one extension package found.
 *
 * The version lines say what a source *is*; this says when Hush last looked and where the package it
 * is running came from. Without it, a row that reads `Version 2.3.10` cannot be told apart from one
 * that was checked months ago, and "which registry is this build from?" - the question two published
 * registries make worth asking - has nowhere to be answered.
 */
@Serializable
data class SpotiFLACExtensionCheck(
    val sourceId: String,
    /** Where the package came from, already shortened for a row; null for a built-in entry. */
    val registry: String? = null,
    val checkedAtMs: Long,
    val outcome: Outcome,
    val fromVersion: String? = null,
    val toVersion: String? = null,
    /** Why a check failed, short enough for one line. */
    val detail: String? = null,
) {
    enum class Outcome {
        /** Looked, and the package already matched its registry. */
        CURRENT,

        /** Looked, and the package was replaced. */
        UPDATED,

        /** Looked, and the registry (or the package) could not be read. */
        FAILED,
    }
}

/**
 * The wording of a per-source check line, and how a registry URL is named in it.
 *
 * Pure on purpose: a timestamp is the one thing here that cannot be read back off the screen later,
 * so the phrases are pinned by tests rather than judged by eye at 3am.
 */
object SpotiFLACExtensionCheckReport {

    /**
     * The owner of a registry URL, as a row can carry it:
     * `https://raw.githubusercontent.com/spotiflacapp/spotiflac-extension/main/registry.json` reads
     * `spotiflacapp`. The repository name is the same words again on every row, so only the part that
     * actually distinguishes one registry from the other is kept.
     */
    fun registryLabel(repositoryId: String?): String? {
        val text = repositoryId?.trim().orEmpty()
        if (text.isEmpty()) return null
        val withoutScheme = text.substringAfter("://", text).substringBefore('?').substringBefore('#')
        val segments = withoutScheme.split('/').filter { it.isNotBlank() }
        if (segments.isEmpty()) return null
        // `host/owner/repo/...` names the owner; a bare `host/file` can only be named by its host.
        return if (segments.size >= 3) segments[1] else segments.first()
    }

    /** `just now`, `12 min ago`, `3 h ago`, `5 d ago` - never a bare timestamp. */
    fun ago(checkedAtMs: Long, nowMs: Long): String {
        val elapsed = (nowMs - checkedAtMs).coerceAtLeast(0L)
        val minutes = elapsed / 60_000L
        return when {
            elapsed < 60_000L -> "just now"
            minutes < 60L -> "$minutes min ago"
            minutes < 24L * 60L -> "${minutes / 60L} h ago"
            else -> "${minutes / (24L * 60L)} d ago"
        }
    }

    /**
     * The line under a source row.
     *
     * [registry] is the label read from the source itself, and is what a row can say before any
     * check has run - the record's own label wins once there is one, because that is the registry
     * the package was actually taken from.
     */
    fun line(check: SpotiFLACExtensionCheck?, registry: String?, nowMs: Long): String? {
        val origin = registryPhrase(check?.registry?.takeIf { it.isNotBlank() } ?: registry)
        if (check == null) {
            return "Not checked yet · $origin"
        }
        return when (check.outcome) {
            SpotiFLACExtensionCheck.Outcome.FAILED -> {
                val reason = check.detail?.trim()?.takeIf { it.isNotEmpty() }
                if (reason == null) {
                    "Check failed ${ago(check.checkedAtMs, nowMs)} · $origin"
                } else {
                    "Check failed ${ago(check.checkedAtMs, nowMs)}: $reason"
                }
            }
            SpotiFLACExtensionCheck.Outcome.UPDATED ->
                "Updated ${ago(check.checkedAtMs, nowMs)} · $origin"
            SpotiFLACExtensionCheck.Outcome.CURRENT ->
                "Checked ${ago(check.checkedAtMs, nowMs)} · $origin"
        }
    }

    private fun registryPhrase(registry: String?): String {
        val label = registry?.trim().orEmpty()
        return if (label.isEmpty()) "built-in" else "from $label"
    }
}

@Serializable
private data class SpotiFLACExtensionCheckIndex(
    val version: Int = 1,
    val checks: List<SpotiFLACExtensionCheck> = emptyList(),
)

/**
 * The per-source check record, one entry per source, kept across restarts.
 *
 * Persisted rather than held in memory because the question it answers - "when was this last
 * checked?" - is asked after a restart, and Android restarts the process freely. The file is tiny
 * (one entry per source, replaced in place) and written once per check, not per track.
 */
@Singleton
class SpotiFLACExtensionCheckStore
    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) {
        private companion object {
            const val FILE_NAME = "extension_checks.json"
            const val DIRECTORY = "spotiflac"
        }

        private val json = Json { encodeDefaults = false; ignoreUnknownKeys = true; prettyPrint = false }
        private val file = File(context.filesDir, "$DIRECTORY/$FILE_NAME")
        private val checks = HashMap<String, SpotiFLACExtensionCheck>()

        @Volatile
        private var loaded = false

        /** Every source's latest check, keyed by source id. */
        fun all(): Map<String, SpotiFLACExtensionCheck> {
            ensureLoaded()
            return synchronized(this) { checks.toMap() }
        }

        /** Replaces the record of each source named, and leaves the others as they were. */
        fun record(entries: List<SpotiFLACExtensionCheck>) {
            if (entries.isEmpty()) return
            ensureLoaded()
            synchronized(this) { entries.forEach { checks[it.sourceId] = it } }
            persist()
        }

        private fun ensureLoaded() {
            if (loaded) return
            synchronized(this) {
                if (loaded) return
                runCatching {
                    if (!file.isFile) return@runCatching
                    json.decodeFromString<SpotiFLACExtensionCheckIndex>(file.readText()).checks
                        .forEach { checks[it.sourceId] = it }
                }
                loaded = true
            }
        }

        private fun persist() {
            runCatching {
                file.parentFile?.mkdirs()
                val snapshot = synchronized(this) { checks.values.toList() }
                file.writeText(
                    json.encodeToString(SpotiFLACExtensionCheckIndex(checks = snapshot)),
                )
            }
        }
    }

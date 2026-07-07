package app.hush.music.utils.sponsorblock

import app.hush.music.App
import app.hush.music.constants.EnableSponsorBlockKey
import app.hush.music.constants.SponsorBlockAutoSkipKey
import app.hush.music.constants.SponsorBlockCategoriesKey
import app.hush.music.utils.dataStore
import app.hush.music.utils.safeDataStoreEdit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import timber.log.Timber

object SponsorBlockManager {
    private const val TAG = "SponsorBlock"
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val _currentSegments = MutableStateFlow<List<SponsorBlockSegment>>(emptyList())
    val currentSegments: StateFlow<List<SponsorBlockSegment>> = _currentSegments.asStateFlow()

    private val _currentVideoId = MutableStateFlow<String?>(null)
    val currentVideoId: StateFlow<String?> = _currentVideoId.asStateFlow()

    private val defaultCategories = setOf(
        SegmentCategory.SPONSOR,
        SegmentCategory.INTRO,
        SegmentCategory.OUTRO,
        SegmentCategory.SELF_PROMO,
        SegmentCategory.INTERACTION,
    )

    private var _isEnabled = false
    private var _autoSkip = true
    private var activeCategories = defaultCategories
    private var trackingJob: Job? = null

    suspend fun initialize() {
        val prefs = App.instance.dataStore.data.first()
        _isEnabled = prefs[EnableSponsorBlockKey] ?: false
        _autoSkip = prefs[SponsorBlockAutoSkipKey] ?: true
        val categoriesRaw = prefs[SponsorBlockCategoriesKey]
        activeCategories = parseCategories(categoriesRaw)
    }

    fun setEnabled(enabled: Boolean) {
        _isEnabled = enabled
        scope.launch {
            App.instance.safeDataStoreEdit { prefs ->
                prefs[EnableSponsorBlockKey] = enabled
            }
        }
        if (!enabled) clearSegments()
    }

    fun isEnabled(): Boolean = _isEnabled

    fun setAutoSkip(skip: Boolean) {
        _autoSkip = skip
        scope.launch {
            App.instance.safeDataStoreEdit { prefs ->
                prefs[SponsorBlockAutoSkipKey] = skip
            }
        }
    }

    fun isAutoSkip(): Boolean = _autoSkip

    fun loadSegments(videoId: String) {
        if (!_isEnabled) return
        _currentVideoId.value = videoId

        trackingJob?.cancel()
        trackingJob = scope.launch {
            val result = SponsorBlockClient.getSegments(videoId, activeCategories)
            result.onSuccess { segments ->
                Timber.tag(TAG).d("Loaded ${segments.size} SponsorBlock segments for $videoId")
                _currentSegments.value = segments
            }.onFailure { error ->
                Timber.tag(TAG).w(error, "Failed to load SponsorBlock segments for $videoId")
                _currentSegments.value = emptyList()
            }
        }
    }

    fun clearSegments() {
        _currentSegments.value = emptyList()
        _currentVideoId.value = null
        trackingJob?.cancel()
    }

    fun getActiveSegment(positionMs: Long): SponsorBlockSegment? {
        val segments = _currentSegments.value
        if (segments.isEmpty()) return null
        val positionSec = positionMs / 1000.0
        return segments.firstOrNull { positionSec >= it.start && positionSec < it.end }
    }

    fun getUpcomingSegment(positionMs: Long): SponsorBlockSegment? {
        val segments = _currentSegments.value
        if (segments.isEmpty()) return null
        val positionSec = positionMs / 1000.0
        return segments
            .filter { it.start > positionSec }
            .minByOrNull { it.start - positionSec }
            ?.takeIf { (it.start - positionSec) in 0.0..5.0 }
    }

    fun getSkipPosition(positionMs: Long): Long? {
        val segment = getActiveSegment(positionMs) ?: return null
        return (segment.end * 1000).toLong()
    }

    fun skipToEnd(segment: SponsorBlockSegment): Long = (segment.end * 1000).toLong()

    fun release() {
        scope.cancel()
    }

    private fun parseCategories(raw: String?): Set<SegmentCategory> {
        if (raw.isNullOrBlank()) return defaultCategories
        val parsed = raw.split(",").mapNotNull { name ->
            SegmentCategory.entries.find { it.name.equals(name.trim(), ignoreCase = true) }
        }.toSet()
        return parsed.ifEmpty { defaultCategories }
    }
}

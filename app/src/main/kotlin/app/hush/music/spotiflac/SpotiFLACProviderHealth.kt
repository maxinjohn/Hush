/*
 * Hush (2026)
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.spotiflac

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * What a provider's own health check says about whether it can serve this client.
 *
 * The extension answers with its own status line, and it is the only thing that can explain a
 * failure the sweep would otherwise report as the track's fault. Measured on the reporting device
 * while Deezer was broken:
 *
 * ```
 * engine test deezer: health={"extension_id":"deezer","status":"offline",
 *   "checks":[{"id":"zarz-api","label":"Zarz API","url":"https://api.zarz.moe/v1/health", ...}]}
 * step=source-test source=deezer verdict=FAIL detail=Zarz API is offline
 * ```
 *
 * and the gateway's own health agreed: `services.deezer.ok=false, error="fetch failed"`. With that
 * provider in the chain every sweep paid for it and reported `Invalid Deezer track ID` - which reads
 * as a broken app, not as a provider that is down on the gateway's side. Knowing the difference is
 * what lets a sweep put such a provider last instead of in front of the user's real sources.
 *
 * Deliberately a parser and nothing else: no runtime, no clock, no cache. The caller owns when to
 * ask and how long to believe the answer.
 */
object SpotiFLACProviderHealth {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** A provider's own answer about whether it can serve. */
    sealed interface Verdict {
        /** The provider says it can serve. */
        data object Available : Verdict

        /** The provider says it cannot, with the reason it gave. */
        data class Unavailable(val reason: String) : Verdict
    }

    /**
     * Classifies one health payload, or null when it says nothing usable.
     *
     * Null is not a soft "available": it means the answer could not be read, and a caller must not
     * demote a provider on that. An unreadable health line is exactly the situation where guessing
     * costs a working source.
     */
    fun verdict(sourceId: String, payload: String?): Verdict? {
        val raw = payload?.trim().orEmpty()
        if (raw.isEmpty()) return null
        val obj = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return null
        val status = obj.string("status")?.lowercase() ?: return null
        return when (status) {
            in AVAILABLE_STATUSES -> Verdict.Available
            in UNAVAILABLE_STATUSES -> Verdict.Unavailable(reason(sourceId, obj, status))
            else -> null
        }
    }

    /**
     * The most specific reason the payload offers: the failing check, else the status itself.
     *
     * Two shapes of check live in the runtime's payloads, both measured on the reporting device: an
     * earlier one that answers `ok=false, error="fetch failed"`, and the one the installed packages
     * send now - `required=true, status="offline", http_status=200, message="Deezer: fetch failed"`.
     * Reading only `ok`, as this did, made the current shape look like a payload with no failing
     * check at all, so the reason fell back to the bare status word while the Test on the same
     * payload said which check had failed: two lines about one outage, disagreeing with each other.
     *
     * A `message` wins over `error`/`detail` because the runtime writes it as a sentence that already
     * names the service, while the machine fields only make sense with the check's label in front.
     */
    private fun reason(sourceId: String, obj: JsonObject, status: String): String {
        val failed = runCatching {
            obj["checks"]?.jsonArray?.firstNotNullOfOrNull { entry ->
                val check = entry as? JsonObject ?: return@firstNotNullOfOrNull null
                val ok = (check["ok"] as? JsonPrimitive)?.content?.lowercase()
                val checkStatus = check.string("status")?.lowercase()
                val failing = ok == "false" ||
                    (checkStatus != null &&
                        checkStatus !in AVAILABLE_STATUSES &&
                        checkStatus !in NON_FAILING_CHECK_STATUSES)
                if (!failing) return@firstNotNullOfOrNull null
                val label = check.string("label") ?: check.string("id") ?: return@firstNotNullOfOrNull null
                check.string("message")
                    ?: (check.string("error") ?: check.string("detail"))?.let { "$label: $it" }
                    ?: label
            }
        }.getOrNull()
        return failed ?: status
    }

    private fun JsonObject.string(key: String): String? =
        runCatching { this[key]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() } }.getOrNull()

    /** Statuses that mean the provider can serve this client. */
    private val AVAILABLE_STATUSES = setOf("ok", "online", "healthy", "available", "ready")

    /** Statuses that mean it cannot. Anything else is unreadable and demotes nobody. */
    private val UNAVAILABLE_STATUSES =
        setOf("offline", "degraded", "unavailable", "error", "failed", "down")

    /**
     * Check statuses that are not failures, for naming the reason inside an unhealthy payload.
     *
     * A provider can be unhealthy overall while individual checks are simply not applicable, and
     * naming one of those as the reason would blame a check nobody ran.
     */
    private val NON_FAILING_CHECK_STATUSES =
        setOf("skipped", "unknown", "pending", "disabled", "n/a", "na")
}

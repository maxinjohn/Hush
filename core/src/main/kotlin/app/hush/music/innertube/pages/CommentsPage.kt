/*
 * Hush (2026)
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.innertube.pages

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

data class Comment(
    val id: String,
    val author: String,
    val authorAvatarUrl: String?,
    val content: String,
    val publishedTimeText: String?,
    val likeCountText: String?,
    val isHearted: Boolean,
)

data class CommentsPage(
    val items: List<Comment>,
    val continuation: String?,
) {
    companion object {
        /**
         * Parses a `/next` response containing the comment item section.
         *
         * Handles both response generations:
         * - modern: comments arrive via `frameworkUpdates.entityBatchUpdate.mutations`
         *   carrying `commentEntityPayload` objects
         * - legacy: `commentThreadRenderer` entries inside `continuationItems`
         */
        fun fromNextResponse(root: JsonObject): CommentsPage {
            val items = LinkedHashMap<String, Comment>()

            // Modern payload format.
            root["frameworkUpdates"]
                ?.jsonObjectSafe()
                ?.get("entityBatchUpdate")
                ?.jsonObjectSafe()
                ?.get("mutations")
                ?.asArraySafe()
                ?.forEach { mutationEl ->
                    val mutation = mutationEl as? JsonObject ?: return@forEach
                    val payload = mutation["payload"]?.jsonObjectSafe() ?: return@forEach
                    val entity = payload["commentEntityPayload"]?.jsonObjectSafe() ?: return@forEach
                    val mutationKey = (mutation["key"] as? JsonPrimitive)?.content
                    parseEntityPayload(mutationKey, entity)?.let { comment ->
                        items[comment.id] = comment
                    }
                }

            // Legacy thread format (also preserves ordering when both exist).
            forEachContinuationItem(root) { item ->
                val thread = item["commentThreadRenderer"]?.jsonObjectSafe() ?: return@forEachContinuationItem
                val renderer = thread["comment"]?.jsonObjectSafe()?.get("commentRenderer")?.jsonObjectSafe() ?: return@forEachContinuationItem
                val id =
                    renderer.str("commentId")
                        ?: return@forEachContinuationItem
                if (!items.containsKey(id)) {
                    items[id] =
                        Comment(
                            id = id,
                            author = renderer.obj("authorText")?.str("simpleText") ?: "",
                            authorAvatarUrl = renderer.obj("authorThumbnail")?.thumbnailsUrl(),
                            content = renderer.obj("contentText").runsText(),
                            publishedTimeText = renderer.obj("publishedTimeText")?.str("simpleText"),
                            likeCountText = renderer.obj("voteCount")?.str("simpleText"),
                            isHearted = false,
                        )
                }
            }

            return CommentsPage(
                items = items.values.toList(),
                continuation = findNextContinuationToken(root),
            )
        }

        /** Extracts the token that starts the comment section from a watch-next response. */
        fun findCommentSectionToken(root: JsonObject): String? {
            var found: String? = null

            walk(root) { obj ->
                if (found != null) return@walk
                val identifier = obj.str("sectionIdentifier")
                if (identifier == "comment-item-section") {
                    found = findFirstToken(obj)
                }
            }
            if (found != null) return found

            walk(root) { obj ->
                if (found != null) return@walk
                val panelId =
                    obj.str("panelIdentifier")
                        ?: obj.str("targetId")
                if (panelId != null && panelId.contains("comments", ignoreCase = true)) {
                    found = findFirstToken(obj)
                }
            }
            return found
        }

        private fun parseEntityPayload(
            key: String?,
            entity: JsonObject,
        ): Comment? {
            val properties = entity.obj("properties")
            val authorObj = entity.obj("author")
            val content =
                properties?.obj("contentText")?.str("content")
                    ?: properties?.obj("text")?.str("content")
            val commentId =
                properties?.str("commentId")
                    ?: key
                    ?: return null

            val toolbar = entity.obj("toolbar")
            val hearted = toolbar?.bool("hearted") == true
            val likes = toolbar?.str("likeCountNotliked") ?: toolbar?.str("likeCountLiked")

            return Comment(
                id = commentId,
                author = authorObj?.str("displayName") ?: "",
                authorAvatarUrl = authorObj?.obj("avatar")?.obj("image")?.thumbnailsUrl(),
                content = content.orEmpty(),
                publishedTimeText =
                    properties?.obj("publishedTimeText")?.str("content")
                        ?: properties?.str("publishedTimeText"),
                likeCountText = likes,
                isHearted = hearted,
            )
        }

        private fun forEachContinuationItem(
            root: JsonObject,
            action: (JsonObject) -> Unit,
        ) {
            val endpoints = root["onResponseReceivedEndpoints"]?.asArraySafe() ?: return
            for (endpointEl in endpoints) {
                val endpoint = endpointEl as? JsonObject ?: continue
                val containers =
                    listOf(
                        endpoint.obj("appendContinuationItemsAction"),
                        endpoint.obj("reloadContinuationItemsCommand"),
                    )
                for (container in containers) {
                    val items = container?.get("continuationItems")?.asArraySafe() ?: continue
                    for (item in items) {
                        (item as? JsonObject)?.let(action)
                    }
                }
            }
        }

        private fun findNextContinuationToken(root: JsonObject): String? {
            val endpoints = root["onResponseReceivedEndpoints"]?.asArraySafe() ?: return null
            for (endpointEl in endpoints) {
                val endpoint = endpointEl as? JsonObject ?: continue
                findFirstToken(endpoint)?.let { return it }
            }
            return null
        }

        private fun findFirstToken(element: JsonElement?): String? {
            when (element) {
                is JsonObject -> {
                    val continuation = element.obj("continuationItemRenderer")
                    val commandToken =
                        continuation
                            ?.obj("continuationEndpoint")
                            ?.obj("continuationCommand")
                            ?.str("token")
                    if (!commandToken.isNullOrEmpty()) return commandToken
                    val buttonToken =
                        continuation
                            ?.obj("button")
                            ?.obj("buttonRenderer")
                            ?.obj("command")
                            ?.obj("continuationCommand")
                            ?.str("token")
                    if (!buttonToken.isNullOrEmpty()) return buttonToken
                    for ((_, value) in element) {
                        findFirstToken(value)?.let { return it }
                    }
                }

                is JsonArray -> {
                    for (item in element) {
                        findFirstToken(item)?.let { return it }
                    }
                }

                else -> Unit
            }
            return null
        }

        private fun walk(
            root: JsonElement,
            action: (JsonObject) -> Unit,
        ) {
            when (root) {
                is JsonObject -> {
                    action(root)
                    for ((_, value) in root) walk(value, action)
                }

                is JsonArray -> for (item in root) walk(item, action)
                else -> Unit
            }
        }

        private fun JsonElement?.jsonObjectSafe(): JsonObject? = this as? JsonObject

        private fun JsonElement?.asArraySafe(): JsonArray? = this as? JsonArray

        private fun JsonObject.str(key: String): String? =
            when (val value = this[key]) {
                is JsonPrimitive ->
                    if (value is JsonNull || value.isString && value.content.isEmpty()) null else value.content

                else -> null
            }

        private fun JsonObject.bool(key: String): Boolean? =
            when (val value = this[key]) {
                is JsonPrimitive -> value.content == "true"
                else -> null
            }

        private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

        private fun JsonObject?.runsText(): String =
            this?.get("runs")?.asArraySafe()
                ?.joinToString("") { run ->
                    ((run as? JsonObject)?.get("text") as? JsonPrimitive)?.content.orEmpty()
                }.orEmpty()

        private fun JsonObject.thumbnailsUrl(): String? {
            val thumbnails = this["thumbnails"]?.asArraySafe() ?: return null
            return ((thumbnails.lastOrNull() as? JsonObject)?.get("url") as? JsonPrimitive)?.content
        }
    }
}

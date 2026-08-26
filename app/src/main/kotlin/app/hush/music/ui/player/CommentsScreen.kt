/*
 * Hush (2026)
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package app.hush.music.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.hush.music.R
import app.hush.music.innertube.InnerTube
import app.hush.music.innertube.YouTube
import app.hush.music.innertube.models.YouTubeClient.Companion.WEB
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

// Self-contained comment data class — no dependency on core CommentsPage
private data class Comment(
    val id: String,
    val author: String,
    val authorAvatarUrl: String?,
    val content: String,
    val publishedTimeText: String?,
    val likeCountText: String?,
    val isHearted: Boolean,
)

private data class CommentsResult(
    val items: List<Comment>,
    val continuation: String?,
)

// Inline helpers for JSON parsing
private fun JsonElement?.jsonObjectSafe(): JsonObject? = this as? JsonObject
private fun JsonElement?.asArraySafe(): JsonArray? = this as? JsonArray

private fun JsonObject.str(key: String): String? =
    when (val value = this[key]) {
        is JsonPrimitive ->
            if (value is JsonNull || (value.isString && value.content.isEmpty())) null else value.content
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

// Self-contained comment fetching — uses InnerTube directly
private object CommentsFetcher {
    private val innerTube = InnerTube()

    init {
        // Share auth state from YouTube
        innerTube.applyAuthState(YouTube.authState)
    }

    suspend fun fetch(videoId: String, continuation: String? = null): Result<CommentsResult> {
        return try {
            val responseText =
                innerTube
                    .next(
                        WEB,
                        videoId = videoId,
                        playlistId = null,
                        playlistSetVideoId = null,
                        index = null,
                        params = null,
                        continuation = continuation,
                    ).bodyAsText()
            val root = Json.parseToJsonElement(responseText).jsonObject
            if (continuation == null) {
                val token = findCommentSectionToken(root)
                    ?: throw IllegalStateException("COMMENTS_SECTION_MISSING")
                val commentsResponseText =
                    innerTube
                        .next(
                            WEB,
                            videoId = null,
                            playlistId = null,
                            playlistSetVideoId = null,
                            index = null,
                            params = null,
                            continuation = token,
                        ).bodyAsText()
                Result.success(parseComments(Json.parseToJsonElement(commentsResponseText).jsonObject))
            } else {
                Result.success(parseComments(root))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun parseComments(root: JsonObject): CommentsResult {
        val items = LinkedHashMap<String, Comment>()

        // Modern payload format (entityBatchUpdate mutations)
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

        // Legacy thread format
        forEachContinuationItem(root) { item ->
            val thread = item["commentThreadRenderer"]?.jsonObjectSafe() ?: return@forEachContinuationItem
            val renderer = thread["comment"]?.jsonObjectSafe()?.get("commentRenderer")?.jsonObjectSafe() ?: return@forEachContinuationItem
            val id = renderer.str("commentId") ?: return@forEachContinuationItem
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

        return CommentsResult(
            items = items.values.toList(),
            continuation = findNextContinuationToken(root),
        )
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

    private fun findCommentSectionToken(root: JsonObject): String? {
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
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommentsScreen(
    videoId: String,
    onBack: () -> Unit,
) {
    val comments = remember { mutableStateListOf<Comment>() }
    var continuation by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var isLoadingMore by remember { mutableStateOf(false) }
    var hasMore by remember { mutableStateOf(true) }
    val listState = rememberLazyListState()

    LaunchedEffect(videoId) {
        isLoading = true
        val result = withContext(Dispatchers.IO) {
            CommentsFetcher.fetch(videoId = videoId)
        }
        result.onSuccess { page: CommentsResult ->
            comments.clear()
            comments.addAll(page.items)
            continuation = page.continuation
            hasMore = page.continuation != null
        }
        isLoading = false
    }

    LaunchedEffect(listState) {
        snapshotFlow { listState.layoutInfo }
            .collect { layoutInfo ->
                val lastVisibleItem = layoutInfo.visibleItemsInfo.lastOrNull()
                val totalItems = layoutInfo.totalItemsCount
                if (
                    lastVisibleItem != null &&
                    lastVisibleItem.index >= totalItems - 3 &&
                    hasMore &&
                    !isLoadingMore &&
                    !isLoading
                ) {
                    isLoadingMore = true
                    val token = continuation
                    if (token != null) {
                        val result = withContext(Dispatchers.IO) {
                            CommentsFetcher.fetch(videoId = videoId, continuation = token)
                        }
                        result.onSuccess { page: CommentsResult ->
                            comments.addAll(page.items)
                            continuation = page.continuation
                            hasMore = page.continuation != null
                        }
                    }
                    isLoadingMore = false
                }
            }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = stringResource(R.string.comments),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        if (comments.isNotEmpty()) {
                            Text(
                                text = "${comments.size} comments",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            painter = painterResource(R.drawable.arrow_back),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                },
                colors =
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                    ),
            )
        },
        containerColor = MaterialTheme.colorScheme.surface,
    ) { innerPadding ->
        if (isLoading) {
            CommentShimmer(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
            )
        } else if (comments.isEmpty()) {
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.no_comments),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                state = listState,
                contentPadding = innerPadding,
                modifier =
                    Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surface),
                verticalArrangement = Arrangement.spacedBy(0.dp),
            ) {
                items(
                    items = comments,
                    key = { it.id },
                ) { comment ->
                    CommentItem(comment = comment)
                }
                if (isLoadingMore) {
                    item {
                        Box(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                strokeWidth = 2.dp,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CommentItem(comment: Comment) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (!comment.authorAvatarUrl.isNullOrBlank()) {
                AsyncImage(
                    model =
                        ImageRequest.Builder(LocalContext.current)
                            .data(comment.authorAvatarUrl)
                            .build(),
                    contentDescription = null,
                    modifier =
                        Modifier
                            .size(32.dp)
                            .clip(CircleShape),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Box(
                    modifier =
                        Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.secondaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = comment.author.take(1).uppercase(),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            }

            Spacer(modifier = Modifier.width(10.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = comment.author,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                val publishedTime = comment.publishedTimeText
                if (!publishedTime.isNullOrBlank()) {
                    Text(
                        text = publishedTime,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            val likeCount = comment.likeCountText
            if (!likeCount.isNullOrBlank()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        painter = painterResource(R.drawable.favorite),
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = likeCount,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        Text(
            text = comment.content,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun CommentShimmer(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        repeat(8) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier =
                        Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Box(
                        modifier =
                            Modifier
                                .height(12.dp)
                                .width((80 + it * 15).dp)
                                .background(
                                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                ),
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Box(
                        modifier =
                            Modifier
                                .height(10.dp)
                                .width(60.dp)
                                .background(
                                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                                ),
                    )
                }
            }
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(12.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
            )
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth(0.75f)
                        .height(12.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)),
            )
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

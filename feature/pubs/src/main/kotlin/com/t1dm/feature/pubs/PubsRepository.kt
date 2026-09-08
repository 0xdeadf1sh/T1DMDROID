package com.t1dm.feature.pubs

import com.t1dm.core.common.T1dmDispatchers
import java.time.Instant
import kotlinx.coroutines.withContext

/** A failure propagates the client's throw untouched and leaves [lastGood] intact. */
class PubsRepository(
    private val client: BlueskyClient,
    private val dispatchers: T1dmDispatchers,
    val actor: String = "adapubs.bsky.social",
) {
    @Volatile
    var lastGood: List<PubPost>? = null

    suspend fun load(limit: Int = 25): List<PubPost> {
        val response = client.authorFeed(actor, limit)
        val posts = withContext(dispatchers.default) {
            response.feed.map { it.post.toPubPost() }
        }
        lastGood = posts
        return posts
    }
}

private fun PostView.toPubPost(): PubPost {
    val handle = author.handle
    return PubPost(
        id = uri,
        postUrl = permalink(uri, handle),
        authorHandle = handle,
        authorName = author.displayName ?: handle,
        authorAvatar = author.avatar,
        text = record.text,
        createdAtMs = runCatching { Instant.parse(record.createdAt).toEpochMilli() }.getOrNull(),
        link = embed?.external?.let { PubLink(it.uri, it.title, it.description, it.thumb) },
        images = embed?.images.orEmpty().mapNotNull { image ->
            val thumb = image.thumb ?: image.fullsize
            val full = image.fullsize ?: image.thumb
            if (thumb == null || full == null) null else PubImage(thumb, full, image.alt)
        },
        likeCount = likeCount,
        repostCount = repostCount,
        replyCount = replyCount,
    )
}

/** Keyed on the resolvable handle, not DID; a non-post shape degrades to the author's profile. */
private fun permalink(atUri: String, handle: String): String {
    val rkey = atUri
        .takeIf { it.startsWith("at://") && it.contains("/app.bsky.feed.post/") }
        ?.substringAfterLast('/')
        ?.takeIf { it.isNotBlank() }
    return if (rkey != null) "https://bsky.app/profile/$handle/post/$rkey"
    else "https://bsky.app/profile/$handle"
}

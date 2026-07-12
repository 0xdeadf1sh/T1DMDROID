package com.t1dm.feature.pubs

import com.t1dm.core.common.T1dmDispatchers
import java.time.Instant
import kotlinx.coroutines.withContext

/**
 * Fetches [actor]'s Bluesky author feed via [BlueskyClient] and maps the wire DTOs to [PubPost]s.
 * The last successful result is memoised in [lastGood] so a screen re-entry paints immediately
 * instead of flashing a spinner; a load failure propagates the client's plain-language throw
 * untouched (the screen turns it into an error state), leaving [lastGood] intact.
 */
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

/**
 * Turn an `at://<did>/app.bsky.feed.post/<rkey>` uri into its public bsky.app permalink, keyed on the
 * resolvable [handle] rather than the opaque DID. The record key is the tail after the last '/';
 * anything that doesn't match the post shape degrades to the author's profile page.
 */
private fun permalink(atUri: String, handle: String): String {
    val rkey = atUri
        .takeIf { it.startsWith("at://") && it.contains("/app.bsky.feed.post/") }
        ?.substringAfterLast('/')
        ?.takeIf { it.isNotBlank() }
    return if (rkey != null) "https://bsky.app/profile/$handle/post/$rkey"
    else "https://bsky.app/profile/$handle"
}

package com.t1dm.feature.pubs

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Non-optional fields are the ones the mapper hard-depends on; the rest default so a sparse post
// still decodes.

@Serializable
internal data class AuthorFeedResponse(
    val feed: List<FeedItem> = emptyList(),
    val cursor: String? = null,
)

@Serializable
internal data class FeedItem(val post: PostView)

@Serializable
internal data class PostView(
    val uri: String,
    val cid: String = "",
    val author: AuthorView,
    val record: PostRecord,
    val embed: EmbedView? = null,
    val replyCount: Int = 0,
    val repostCount: Int = 0,
    val likeCount: Int = 0,
    val quoteCount: Int = 0,
    val indexedAt: String = "",
)

@Serializable
internal data class AuthorView(
    val handle: String,
    val displayName: String? = null,
    val avatar: String? = null,
)

@Serializable
internal data class PostRecord(
    val text: String = "",
    val createdAt: String? = null,
)

/** Only the image and external shapes are modelled; any other [type] carries neither, so the
 *  mapper degrades it to a text-only post. */
@Serializable
internal data class EmbedView(
    @SerialName("\$type") val type: String? = null,
    val external: ExternalView? = null,
    val images: List<ImageView> = emptyList(),
)

@Serializable
internal data class ExternalView(
    val uri: String,
    val title: String = "",
    val description: String = "",
    val thumb: String? = null,
)

@Serializable
internal data class ImageView(
    val thumb: String? = null,
    val fullsize: String? = null,
    val alt: String = "",
)

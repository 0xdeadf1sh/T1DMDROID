package com.t1dm.feature.pubs

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire DTOs for the public AppView `app.bsky.feed.getAuthorFeed` response — modelling only what
 * adapubs actually emits. The decoder is lenient (`ignoreUnknownKeys`), so every field the feed
 * carries but the UI ignores (facets, aspect ratios, bookmark counts, viewer state, …) is dropped
 * rather than fought. Non-optional fields are the ones the mapper hard-depends on; everything else
 * defaults so a sparse post never fails to decode.
 */

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

/**
 * One embed view, discriminated by [type] (`app.bsky.embed.images#view`,
 * `app.bsky.embed.external#view`, `…video#view`, `…record#view`, …). Only the image and external
 * shapes are modelled; any other kind — video, a quote/record embed, an unknown future type — simply
 * carries neither [external] nor a non-empty [images], so the mapper degrades it to a text-only post.
 */
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

package com.t1dm.feature.pubs

/**
 * The UI-facing shape of a single feed post — the DTOs in `BlueskyDto.kt` are the wire form; these
 * are what [PubsScreen] renders. Everything the card needs is resolved up front (permalink,
 * display name fallback, absolute image URLs), so the composable stays free of AT-protocol detail.
 */
data class PubPost(
    val id: String,
    val postUrl: String,
    val authorHandle: String,
    val authorName: String,
    val authorAvatar: String?,
    val text: String,
    val createdAtMs: Long?,
    val link: PubLink?,
    val images: List<PubImage>,
    val likeCount: Int,
    val repostCount: Int,
    val replyCount: Int,
)

/** An external link card (a DOI/journal reference); [thumbUrl] is absent on ~a quarter of cards. */
data class PubLink(
    val uri: String,
    val title: String,
    val description: String,
    val thumbUrl: String?,
)

/** An embedded image; [thumbUrl] drives the grid, [fullUrl] the tap-through, [alt] the a11y label. */
data class PubImage(
    val thumbUrl: String,
    val fullUrl: String,
    val alt: String,
)

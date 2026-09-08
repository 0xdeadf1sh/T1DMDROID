package com.t1dm.feature.pubs

/** UI-facing post form; wire form is `BlueskyDto.kt`; resolved up front, no AT-protocol detail. */
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

data class PubLink(
    val uri: String,
    val title: String,
    val description: String,
    val thumbUrl: String?,
)

data class PubImage(
    val thumbUrl: String,
    val fullUrl: String,
    val alt: String,
)

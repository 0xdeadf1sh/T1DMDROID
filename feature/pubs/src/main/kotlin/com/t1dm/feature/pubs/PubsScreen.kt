package com.t1dm.feature.pubs

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.t1dm.core.design.HapticEvent
import com.t1dm.core.design.LocalAnimationsEnabled
import com.t1dm.core.design.rememberT1dmHaptics
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * The research-feed surface (`:feature:pubs`): adapubs.bsky.social's author feed rendered as native
 * cards. It seeds from [PubsRepository.lastGood] so a tab re-entry paints instantly, loads once on
 * first composition, and offers pull-to-refresh. Every failure becomes a plain-language line rather
 * than a bare HTTP code; colours come solely from the active theme, and the only motion — the image
 * cross-fade — is gated on [LocalAnimationsEnabled].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PubsScreen(repository: PubsRepository) {
    val scope = rememberCoroutineScope()
    var posts by remember { mutableStateOf(repository.lastGood) }
    var error by remember { mutableStateOf<String?>(null) }
    var refreshing by remember { mutableStateOf(false) }
    var job by remember { mutableStateOf<Job?>(null) }
    val haptics = rememberT1dmHaptics()

    fun load(userInitiated: Boolean) {
        if (userInitiated) refreshing = true
        job?.cancel()
        job = scope.launch {
            try {
                val result = repository.load()
                posts = result
                error = null
                refreshing = false
            } catch (c: CancellationException) {
                throw c // a superseded load owns none of the UI state; its replacement will set it
            } catch (t: Throwable) {
                error = friendlyError(t)
                refreshing = false
            }
        }
    }

    LaunchedEffect(Unit) { load(userInitiated = false) }

    // A pull-to-refresh has no button to press, so the release-into-refresh is the only place the
    // gesture can be acknowledged; the outcome is announced separately when the error lands, because
    // a network failure arrives seconds later with nothing on screen having visibly moved.
    LaunchedEffect(error) { if (error != null) haptics.perform(HapticEvent.Warn) }

    val pullState = rememberPullToRefreshState()
    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = { haptics.perform(HapticEvent.DragEnd); load(userInitiated = true) },
        state = pullState,
        modifier = Modifier.fillMaxSize(),
    ) {
        val current = posts
        when {
            current != null && current.isEmpty() && error == null -> CenteredMessage {
                Text(
                    "No posts yet",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
            current != null -> Feed(current, error) { load(userInitiated = true) }
            error != null && !refreshing -> CenteredMessage {
                Text(
                    error!!,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = { haptics.perform(HapticEvent.Tap); load(userInitiated = true) },
                ) { Text("Try again") }
            }
            else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }
    }
}

@Composable
private fun Feed(posts: List<PubPost>, refreshError: String?, onRetry: () -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (refreshError != null) {
            item { RefreshErrorBanner(refreshError, onRetry) }
        }
        items(posts, key = { it.id }) { PostCard(it) }
    }
}

@Composable
private fun PostCard(post: PubPost) {
    val context = LocalContext.current
    val haptics = rememberT1dmHaptics()
    Card(
        // NavSwitch, not Tap: the press leaves T1DM entirely for the browser, and a departure ought
        // to feel like the destination changes it shares a vocabulary with.
        onClick = { haptics.perform(HapticEvent.NavSwitch); openUrl(context, post.postUrl) },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            AuthorRow(post)
            if (post.text.isNotBlank()) {
                Text(post.text, style = MaterialTheme.typography.bodyMedium)
            }
            post.link?.let { LinkCard(it) }
            if (post.images.isNotEmpty()) ImageBlock(post.images)
            CountsRow(post)
        }
    }
}

@Composable
private fun AuthorRow(post: PubPost) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val avatar = post.authorAvatar
        if (avatar != null) {
            FeedImage(avatar, null, Modifier.size(40.dp).clip(CircleShape), ContentScale.Crop)
        } else {
            Box(
                Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            )
        }
        Column(Modifier.weight(1f)) {
            Text(
                post.authorName,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "@${post.authorHandle}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        val rel = relativeTime(post.createdAtMs)
        if (rel.isNotEmpty()) {
            Text(
                rel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun LinkCard(link: PubLink) {
    val context = LocalContext.current
    val haptics = rememberT1dmHaptics()
    Surface(
        onClick = { haptics.perform(HapticEvent.NavSwitch); openUrl(context, link.uri) },
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            link.thumbUrl?.let {
                FeedImage(
                    it,
                    null,
                    Modifier.fillMaxWidth().heightIn(max = 180.dp),
                    // Fit, not Crop: adapubs' link-card thumbs are text-bearing graphical abstracts;
                    // cropping clips the words at the edges. Letterbox instead.
                    ContentScale.Fit,
                )
            }
            Column(
                Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    hostOf(link.uri),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (link.title.isNotBlank()) {
                    Text(
                        link.title,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (link.description.isNotBlank()) {
                    Text(
                        link.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun ImageBlock(images: List<PubImage>) {
    val shape = MaterialTheme.shapes.medium
    if (images.size == 1) {
        val img = images.first()
        FeedImage(
            img.fullUrl,
            img.alt,
            Modifier.fillMaxWidth().heightIn(max = 320.dp).clip(shape),
            // Fit so a whole posted infographic stays legible; Crop would clip its margins.
            ContentScale.Fit,
        )
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            images.chunked(2).forEach { row ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    row.forEach { img ->
                        FeedImage(
                            img.thumbUrl,
                            img.alt,
                            Modifier.weight(1f).height(150.dp).clip(shape),
                            ContentScale.Crop,
                        )
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun CountsRow(post: PubPost) {
    val parts = listOf(
        plural(post.likeCount, "like", "likes"),
        plural(post.repostCount, "repost", "reposts"),
        plural(post.replyCount, "reply", "replies"),
    )
    Text(
        parts.joinToString("  ·  "),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun RefreshErrorBanner(message: String, onRetry: () -> Unit) {
    val haptics = rememberT1dmHaptics()
    Surface(
        onClick = { haptics.perform(HapticEvent.Tap); onRetry() },
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.errorContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.padding(12.dp),
        )
    }
}

/** A single, viewport-filling scroll item so the empty/error states still take the pull gesture. */
@Composable
private fun CenteredMessage(content: @Composable () -> Unit) {
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Column(
                modifier = Modifier.fillParentMaxSize().padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) { content() }
        }
    }
}

@Composable
private fun FeedImage(
    url: String,
    contentDescription: String?,
    modifier: Modifier,
    contentScale: ContentScale,
) {
    val animate = LocalAnimationsEnabled.current
    val context = LocalContext.current
    AsyncImage(
        model = ImageRequest.Builder(context).data(url).crossfade(animate).build(),
        contentDescription = contentDescription,
        contentScale = contentScale,
        modifier = modifier,
    )
}

private fun openUrl(context: Context, url: String) {
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

private fun friendlyError(t: Throwable): String =
    t.message?.takeIf { it.isNotBlank() }
        ?: "Couldn't load the feed"

private fun plural(n: Int, one: String, many: String): String = "$n ${if (n == 1) one else many}"

private fun hostOf(uri: String): String =
    runCatching { Uri.parse(uri).host?.removePrefix("www.") }.getOrNull()?.takeIf { it.isNotBlank() }
        ?: uri

private val DATE_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d")

private fun relativeTime(createdAtMs: Long?, nowMs: Long = System.currentTimeMillis()): String {
    if (createdAtMs == null) return ""
    val diff = (nowMs - createdAtMs).coerceAtLeast(0)
    val minutes = diff / 60_000
    val hours = diff / 3_600_000
    val days = diff / 86_400_000
    return when {
        minutes < 1 -> "now"
        minutes < 60 -> "${minutes}m"
        hours < 24 -> "${hours}h"
        days < 7 -> "${days}d"
        days < 28 -> "${days / 7}w"
        else -> DATE_FMT.format(Instant.ofEpochMilli(createdAtMs).atZone(ZoneId.systemDefault()))
    }
}

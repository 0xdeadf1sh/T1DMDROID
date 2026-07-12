package com.t1dm.feature.pubs

import com.t1dm.core.common.T1dmDispatchers
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Wire-level tests for the AppView reader and its mapping. A [MockWebServer] serves a trimmed but
 * byte-faithful `getAuthorFeed` body carrying the three embed shapes adapubs actually emits — an
 * `images#view`, an `external#view`, and a bare text-only post whose link lives in facets — and the
 * assertions cover both the lenient decode (unknown keys, the `$type` discriminator) and the
 * DTO→[PubPost] projection (at:// permalink, external link card, resolved image URLs, counts).
 */
class BlueskyClientTest {

    private lateinit var server: MockWebServer

    @Before fun setUp() { server = MockWebServer(); server.start() }

    @After fun tearDown() { server.shutdown() }

    /** Everything on Unconfined — the client's `withContext(io)` hops run inline under [runTest]. */
    private class ImmediateDispatchers : T1dmDispatchers {
        override val main = Dispatchers.Unconfined
        override val default = Dispatchers.Unconfined
        override val io = Dispatchers.Unconfined
        override val inference = Dispatchers.Unconfined
    }

    private val dispatchers = ImmediateDispatchers()

    private fun client(): BlueskyClient =
        BlueskyClient(dispatchers = dispatchers, baseUrl = server.url("").toString().trimEnd('/'))

    @Test
    fun authorFeedParsesTheThreeEmbedShapes() = runTest {
        server.enqueue(MockResponse().setBody(FEED_JSON))

        val resp = client().authorFeed(actor = ACTOR, limit = 25)

        assertEquals(3, resp.feed.size)
        assertEquals("2026-07-06T18:35:03.606Z", resp.cursor)

        val images = resp.feed[0].post
        assertEquals("Footwear adherence improves with a personalized intervention.", images.record.text)
        assertEquals(1, images.embed?.images?.size)
        assertEquals(
            "https://cdn.bsky.app/img/feed_fullsize/plain/did:plc:ma4sng77czs6hvmzbqjl2wg7/blob1",
            images.embed?.images?.first()?.fullsize,
        )
        assertNull(images.embed?.external)
        assertEquals(3, images.likeCount)
        assertEquals(2, images.repostCount)
        assertEquals(1, images.replyCount)

        val external = resp.feed[1].post
        assertEquals("https://doi.org/10.2337/db25-0769", external.embed?.external?.uri)
        assertTrue(external.embed?.images?.isEmpty() == true)

        val textOnly = resp.feed[2].post
        assertNull(textOnly.embed)

        val recorded = server.takeRequest()
        assertEquals("GET", recorded.method)
        val path = recorded.path!!
        assertTrue(path.startsWith("/xrpc/app.bsky.feed.getAuthorFeed"))
        assertTrue(path.contains("actor=adapubs.bsky.social"))
        assertTrue(path.contains("filter=posts_no_replies"))
        assertTrue(path.contains("limit=25"))
    }

    @Test
    fun repositoryLoadMapsWireDtosToPubPosts() = runTest {
        server.enqueue(MockResponse().setBody(FEED_JSON))
        val repo = PubsRepository(client = client(), dispatchers = dispatchers)
        assertNull(repo.lastGood)

        val posts = repo.load()

        assertEquals(3, posts.size)
        assertEquals(posts, repo.lastGood)

        val imagePost = posts[0]
        assertEquals(
            "at://did:plc:ma4sng77czs6hvmzbqjl2wg7/app.bsky.feed.post/3mqfhawt6be2r",
            imagePost.id,
        )
        assertEquals(
            "https://bsky.app/profile/adapubs.bsky.social/post/3mqfhawt6be2r",
            imagePost.postUrl,
        )
        assertEquals("adapubs.bsky.social", imagePost.authorHandle)
        assertEquals("ADA Professional Publications", imagePost.authorName)
        assertEquals(
            "https://cdn.bsky.app/img/avatar/plain/did:plc:ma4sng77czs6hvmzbqjl2wg7/ava@jpeg",
            imagePost.authorAvatar,
        )
        assertNull(imagePost.link)
        assertEquals(1, imagePost.images.size)
        assertEquals(
            "https://cdn.bsky.app/img/feed_thumbnail/plain/did:plc:ma4sng77czs6hvmzbqjl2wg7/blob1",
            imagePost.images[0].thumbUrl,
        )
        assertEquals(
            "https://cdn.bsky.app/img/feed_fullsize/plain/did:plc:ma4sng77czs6hvmzbqjl2wg7/blob1",
            imagePost.images[0].fullUrl,
        )
        assertEquals("Poster summarizing the footwear adherence study.", imagePost.images[0].alt)
        assertEquals(3, imagePost.likeCount)
        assertEquals(2, imagePost.repostCount)
        assertEquals(1, imagePost.replyCount)
        assertEquals(
            Instant.parse("2026-07-11T20:00:07.428553662Z").toEpochMilli(),
            imagePost.createdAtMs,
        )

        val linkPost = posts[1]
        assertEquals(
            "https://bsky.app/profile/adapubs.bsky.social/post/3moojkudbp722",
            linkPost.postUrl,
        )
        val link = linkPost.link
        assertNotNull(link)
        assertEquals("https://doi.org/10.2337/db25-0769", link!!.uri)
        assertEquals("The Potential of Composite Pig Islets-Kidney Xenotransplantation", link.title)
        assertEquals(
            "One-third of patients with diabetes will develop end-stage kidney disease.",
            link.description,
        )
        assertEquals(
            "https://cdn.bsky.app/img/feed_thumbnail/plain/did:plc:ma4sng77czs6hvmzbqjl2wg7/blob2",
            link.thumbUrl,
        )
        assertTrue(linkPost.images.isEmpty())

        val textPost = posts[2]
        assertEquals(
            "https://bsky.app/profile/adapubs.bsky.social/post/3mqe2t6ib542y",
            textPost.postUrl,
        )
        assertNull(textPost.link)
        assertTrue(textPost.images.isEmpty())
        assertEquals("A diabetes diagnosis should not determine fitness for a job.", textPost.text)
        assertEquals("adapubs.bsky.social", textPost.authorName) // displayName absent → handle fallback
        assertNull(textPost.authorAvatar)
    }

    @Test
    fun authorFeedThrowsPlainLanguageOnServerError() = runTest {
        server.enqueue(MockResponse().setResponseCode(500).setBody("boom"))

        val thrown = try {
            client().authorFeed(actor = ACTOR)
            null
        } catch (e: Exception) {
            e
        }

        assertNotNull("expected authorFeed to throw on a 5xx", thrown)
        val message = thrown!!.message
        assertNotNull("a failure must carry a message", message)
        assertTrue("message must be plain language, never a bare code", message!!.any { it.isLetter() })
    }

    private companion object {
        const val ACTOR = "adapubs.bsky.social"

        // Three real, trimmed adapubs posts (field names byte-exact): an images#view, an external#view,
        // and a text-only post. `${'$'}type`, `aspectRatio`, and `bookmarkCount` exercise ignoreUnknownKeys.
        val FEED_JSON = """
            {
              "feed": [
                {
                  "post": {
                    "uri": "at://did:plc:ma4sng77czs6hvmzbqjl2wg7/app.bsky.feed.post/3mqfhawt6be2r",
                    "cid": "bafyreify4b4lv3xuwa7bswm72vakbh3wjlaed7quiypl2ifkchm3ol3eka",
                    "author": {
                      "did": "did:plc:ma4sng77czs6hvmzbqjl2wg7",
                      "handle": "adapubs.bsky.social",
                      "displayName": "ADA Professional Publications",
                      "avatar": "https://cdn.bsky.app/img/avatar/plain/did:plc:ma4sng77czs6hvmzbqjl2wg7/ava@jpeg"
                    },
                    "record": {
                      "${'$'}type": "app.bsky.feed.post",
                      "text": "Footwear adherence improves with a personalized intervention.",
                      "createdAt": "2026-07-11T20:00:07.428553662Z"
                    },
                    "replyCount": 1,
                    "repostCount": 2,
                    "likeCount": 3,
                    "quoteCount": 0,
                    "bookmarkCount": 4,
                    "indexedAt": "2026-07-11T20:00:08.168Z",
                    "embed": {
                      "${'$'}type": "app.bsky.embed.images#view",
                      "images": [
                        {
                          "thumb": "https://cdn.bsky.app/img/feed_thumbnail/plain/did:plc:ma4sng77czs6hvmzbqjl2wg7/blob1",
                          "fullsize": "https://cdn.bsky.app/img/feed_fullsize/plain/did:plc:ma4sng77czs6hvmzbqjl2wg7/blob1",
                          "alt": "Poster summarizing the footwear adherence study.",
                          "aspectRatio": { "height": 1034, "width": 2000 }
                        }
                      ]
                    }
                  }
                },
                {
                  "post": {
                    "uri": "at://did:plc:ma4sng77czs6hvmzbqjl2wg7/app.bsky.feed.post/3moojkudbp722",
                    "cid": "bafyreiewilrhhtztnzylukguo5wnqc65hm5u5chucsaycudphlzaisqvfm",
                    "author": {
                      "did": "did:plc:ma4sng77czs6hvmzbqjl2wg7",
                      "handle": "adapubs.bsky.social",
                      "displayName": "ADA Professional Publications"
                    },
                    "record": {
                      "${'$'}type": "app.bsky.feed.post",
                      "text": "Composite pig islet-kidney xenotransplantation.",
                      "createdAt": "2026-06-19T23:45:02.227822609Z"
                    },
                    "replyCount": 0,
                    "repostCount": 0,
                    "likeCount": 0,
                    "quoteCount": 0,
                    "indexedAt": "2026-06-19T23:45:02.466Z",
                    "embed": {
                      "${'$'}type": "app.bsky.embed.external#view",
                      "external": {
                        "uri": "https://doi.org/10.2337/db25-0769",
                        "title": "The Potential of Composite Pig Islets-Kidney Xenotransplantation",
                        "description": "One-third of patients with diabetes will develop end-stage kidney disease.",
                        "thumb": "https://cdn.bsky.app/img/feed_thumbnail/plain/did:plc:ma4sng77czs6hvmzbqjl2wg7/blob2"
                      }
                    }
                  }
                },
                {
                  "post": {
                    "uri": "at://did:plc:ma4sng77czs6hvmzbqjl2wg7/app.bsky.feed.post/3mqe2t6ib542y",
                    "cid": "bafyreigxokqw3wxhcfckfsrz2eb6e6u3gpxsp3woydxixvijpdleoqvgge",
                    "author": {
                      "did": "did:plc:ma4sng77czs6hvmzbqjl2wg7",
                      "handle": "adapubs.bsky.social"
                    },
                    "record": {
                      "${'$'}type": "app.bsky.feed.post",
                      "text": "A diabetes diagnosis should not determine fitness for a job.",
                      "createdAt": "2026-07-11T06:45:00.813082459Z",
                      "facets": [
                        {
                          "features": [
                            { "${'$'}type": "app.bsky.richtext.facet#link", "uri": "https://bit.ly/4vVZh5H" }
                          ],
                          "index": { "byteStart": 231, "byteEnd": 253 }
                        }
                      ]
                    },
                    "replyCount": 0,
                    "repostCount": 0,
                    "likeCount": 0,
                    "quoteCount": 0,
                    "indexedAt": "2026-07-11T06:45:01.367Z"
                  }
                }
              ],
              "cursor": "2026-07-06T18:35:03.606Z"
            }
        """.trimIndent()
    }
}

/*******************************************************************************
 * This file is part of RedReader.
 *
 * RedReader is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * RedReader is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with RedReader.  If not, see <http://www.gnu.org/licenses/>.
 ******************************************************************************/
package org.quantumbadger.redreader.reddit.prepared

import androidx.appcompat.app.AppCompatActivity
import org.quantumbadger.redreader.R
import org.quantumbadger.redreader.common.UriString
import org.quantumbadger.redreader.reddit.PostCommentSort
import org.quantumbadger.redreader.reddit.kthings.RedditIdAndType
import org.quantumbadger.redreader.reddit.kthings.RedditPost
import org.quantumbadger.redreader.reddit.kthings.ImageMetadata
import org.quantumbadger.redreader.reddit.kthings.MaybeParseError
import java.net.URI
import java.net.URISyntaxException
import org.quantumbadger.redreader.reddit.prepared.bodytext.BodyElement
import org.quantumbadger.redreader.reddit.prepared.html.HtmlReader
import org.quantumbadger.redreader.reddit.things.RedditThingWithIdAndType

class RedditParsedPost(
	activity: AppCompatActivity,
	val src: RedditPost,
	parseSelfText: Boolean
) : RedditThingWithIdAndType {

	val title: String = src.title?.decoded?.replace('\n', ' ')?.trim() ?: "[null]"

	val url: UriString?
	val selfText: BodyElement?
	val flairText: String?
	val domain: String

	val permalink = src.permalink.decoded
	val isStickied = src.stickied
	val thumbnailUrl = UriString.fromNullable(src.thumbnail?.decoded)

	val isPreviewEnabled = src.preview?.enabled == true

	val isVideoPreview = src.preview?.run {
		src.is_video
				|| images?.firstOrNull()?.variants?.mp4 != null
				|| reddit_video_preview != null
				|| when (src.domain?.decoded) {
			"v.redd.it", "streamable.com", "gfycat.com" -> true
			else -> false
		}
	} ?: false

	val suggestedCommentSort: PostCommentSort?
		get() = if (src.suggested_sort == null) {
			null
		} else PostCommentSort.lookup(src.suggested_sort)

	val isArchived = src.archived

	val isLocked = src.locked

	val canModerate = src.can_mod_post

	val author: String?
		get() = src.author?.decoded

	val distinguished: String?
		get() = src.distinguished

	val rawSelfTextMarkdown = src.selftext?.decoded

	val isSpoiler = src.spoiler

	val unescapedSelfText = src.selftext?.decoded

	val subreddit = src.subreddit.decoded

	// TODO do we still need this? I think it's because we add the score at a later point
	val scoreExcludingOwnVote: Int
		get() {
			var score = src.score
			when (src.likes) {
				true -> score--
				false -> score++
				null -> {}
			}
			return score
		}

	val commentCount = src.num_comments

	val goldAmount = src.gilded

	val isCrosspost = src.crosspost_parent

	val isNsfw = src.over_18

	val createdTimeUTC = src.created_utc.value

	val isSelfPost = src.is_self

	val mediaType = PostMediaType.forPost(src)

	val upvotePercentage = src.upvote_ratio?.times(100.0)?.toInt()

    init {

		url = src.findUrl()

		selfText = if (parseSelfText && src.selftext_html != null) {
			HtmlReader.parse(src.selftext_html.decoded, activity)
		} else {
			null
		}

		flairText = if (src.link_flair_text != null && src.link_flair_text.decoded.isNotEmpty()) {
			src.link_flair_text.decoded
		} else {
			null
		}

		domain = src.domain?.decoded ?: activity.getString(R.string.post_domain_deleted)
    }

    override fun getIdAlone(): String {
        return src.idAlone
    }

    override fun getIdAndType(): RedditIdAndType {
        return src.idAndType
    }

	fun hasSelfText(): Boolean {
		return rawSelfTextMarkdown != null && rawSelfTextMarkdown.length > 1
	}

    data class ImagePreviewDetails(
		@JvmField val url: UriString,
		@JvmField val width: Int,
		@JvmField val height: Int
	)

	fun hasImagePreview(): Boolean = getPreview(1, 1) != null

	fun getPreview(minWidth: Int, minHeight: Int): ImagePreviewDetails? {
		// Gallery posts often have no top-level preview. Choose a bounded still from the
		// first usable attachment, not its unbounded full-resolution source by default.
		for (entry in src.gallery_data?.items.orEmpty()) {
			val item = (entry as? MaybeParseError.Ok)?.value ?: continue
			val metadata = (src.media_metadata?.get(item.media_id)
				as? MaybeParseError.Ok)?.value ?: continue
			if (metadata.status != "valid" || (metadata.e != "Image" && metadata.e != "AnimatedImage")) {
				continue
			}
			val candidates = (metadata.p.orEmpty() + metadata.s).mapNotNull(::galleryCandidate)
			selectPreview(candidates, minWidth, minHeight)?.let {
				return it
			}
		}
		return src.preview?.images?.firstOrNull()?.run {
			getPreviewInternal(this, minWidth, minHeight)
		}
	}

	private fun galleryCandidate(image: ImageMetadata): RedditPost.Preview.ImageDetails? {
		if (image.x !in 1L..Int.MAX_VALUE.toLong() || image.y !in 1L..Int.MAX_VALUE.toLong()) return null
		val url = image.u ?: image.gif ?: return null
		return RedditPost.Preview.ImageDetails(url, image.x.toInt(), image.y.toInt())
	}

    fun getPreviewMP4(minWidth: Int, minHeight: Int)
		= src.preview?.images?.firstOrNull()?.variants?.mp4?.apply {
			getPreviewInternal(this, minWidth, minHeight)
	}

	private fun getPreviewInternal(
		image: RedditPost.Preview.ImageBase,
		minWidth: Int,
		minHeight: Int
	): ImagePreviewDetails? = selectPreview(
		listOfNotNull(image.source) + image.resolutions.orEmpty(), minWidth, minHeight
	)

	private fun selectPreview(
		candidates: List<RedditPost.Preview.ImageDetails>,
		minWidth: Int,
		minHeight: Int
	): ImagePreviewDetails? {
		val valid = candidates.filter { candidate ->
			candidate.width >= 50 && candidate.height >= 50 && isPreviewUrl(candidate.url.decoded)
		}
		val sufficient = valid.filter { it.width >= minWidth && it.height >= minHeight }
		val best = sufficient.minByOrNull { it.width.toLong() * it.height }
			?: valid.maxByOrNull { it.width.toLong() * it.height }
		return best?.let { ImagePreviewDetails(UriString(it.url.decoded), it.width, it.height) }
	}

	private fun isPreviewUrl(value: String): Boolean = try {
		val uri = URI(value)
		(uri.scheme.equals("https", true) || uri.scheme.equals("http", true))
			&& uri.host != null && uri.userInfo == null
	} catch (error: URISyntaxException) {
		false
	}
}

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

import androidx.annotation.StringRes
import org.quantumbadger.redreader.R
import org.quantumbadger.redreader.common.UriString
import org.quantumbadger.redreader.reddit.kthings.RedditPost

enum class PostMediaType(val preferenceValue: String, @get:StringRes val titleRes: Int) {
	VIDEO("video", R.string.media_type_video),
	IMAGE("image", R.string.media_type_image),
	GALLERY("gallery", R.string.media_type_gallery),
	SELF("self", R.string.media_type_self),
	LINK("link", R.string.media_type_link),
	CROSSPOST("crosspost", R.string.media_type_crosspost);

	companion object {

		@JvmStatic
		fun forPost(post: RedditPost): PostMediaType {

			// Crossposts are an explicit category, independent of their linked content.
			if (post.crosspost_parent != null) {
				return CROSSPOST
			}
			if (post.is_self) {
				return SELF
			}
			if (post.gallery_data != null) {
				return GALLERY
			}
			if (post.is_video || post.media?.reddit_video != null
				|| post.preview?.reddit_video_preview != null
				|| post.preview?.images.orEmpty().any { it.variants.mp4 != null }
			) {
				return VIDEO
			}

			val url = post.url_overridden_by_dest?.decoded ?: post.url?.decoded ?: return LINK
			val uri = UriString(url).toUri()
			val host = uri.host?.lowercase()?.removePrefix("www.")
			val path = uri.path.orEmpty().lowercase()
			val extension = path.substringAfterLast('.', "")

			return when {
				host in videoHosts || extension in videoExtensions -> VIDEO
				host == "imgur.com" && (path.startsWith("/a/") || path.startsWith("/gallery/")) -> GALLERY
				host == "reddit.com" && path.startsWith("/gallery/") -> GALLERY
				host in imageHosts || extension in imageExtensions -> IMAGE
				else -> LINK
			}
		}

		private val videoHosts = setOf("v.redd.it", "streamable.com", "gfycat.com", "redgifs.com")
		private val videoExtensions = setOf("mp4", "webm", "mov", "m4v", "m3u8")
		private val imageHosts = setOf("i.redd.it", "i.imgur.com")
		private val imageExtensions = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "avif")
	}
}

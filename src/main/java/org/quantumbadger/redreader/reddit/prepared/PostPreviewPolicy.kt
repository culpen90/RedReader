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

import android.content.Context
import org.quantumbadger.redreader.common.General
import org.quantumbadger.redreader.common.PrefsUtility
import org.quantumbadger.redreader.reddit.kthings.RedditPost

object PostPreviewPolicy {

	@JvmStatic
	fun isAllowed(context: Context, post: RedditPost): Boolean = isAllowed(
		PrefsUtility.images_inline_image_previews().isEnabled(General.isConnectionWifi(context)),
		post.over_18, post.spoiler,
		PrefsUtility.images_inline_image_previews_nsfw(),
		PrefsUtility.images_inline_image_previews_spoiler()
	)

	@JvmStatic
	fun isAllowed(
		networkAllowed: Boolean,
		nsfw: Boolean,
		spoiler: Boolean,
		allowNsfw: Boolean,
		allowSpoilers: Boolean
	): Boolean = networkAllowed && (!nsfw || allowNsfw) && (!spoiler || allowSpoilers)
}

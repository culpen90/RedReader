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

package org.quantumbadger.redreader.reddit.api

import org.quantumbadger.redreader.account.RedditAccount
import org.quantumbadger.redreader.common.StringUtils
import org.quantumbadger.redreader.reddit.url.MultiredditPostListURL

/** The full identity of a multi, independent of the mutable default account. */
data class MultiredditTarget private constructor(
	@JvmField val owner: String,
	@JvmField val name: String
) {

	fun getDisplayPath(): String = "/user/$owner/m/$name"

	fun isOwnedBy(account: RedditAccount): Boolean =
		!account.isAnonymous && owner.equals(account.canonicalUsername, ignoreCase = true)

	fun requireOwnedBy(account: RedditAccount) {
		require(isOwnedBy(account)) { "The captured account does not own this multireddit" }
	}

	companion object {
		private val ownerPattern = Regex("[A-Za-z0-9_-]+")
		private val namePattern = Regex("[A-Za-z0-9_]{1,50}")

		@JvmStatic
		fun isValidName(name: String): Boolean = namePattern.matches(name)

		@JvmStatic
		fun forName(account: RedditAccount, name: String): MultiredditTarget {
			require(!account.isAnonymous) { "A signed-in account is required" }
			return create(account.canonicalUsername, name)
		}

		@JvmStatic
		fun fromListing(account: RedditAccount, url: MultiredditPostListURL): MultiredditTarget {
			require(!account.isAnonymous) { "A signed-in account is required" }
			return create(url.username ?: account.canonicalUsername, url.name)
		}

		private fun create(owner: String, name: String): MultiredditTarget {
			require(ownerPattern.matches(owner)) { "Invalid multireddit owner" }
			require(isValidName(name)) { "Invalid multireddit name" }
			return MultiredditTarget(StringUtils.asciiLowercase(owner), name)
		}
	}
}

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

package org.quantumbadger.redreader.test.general

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.quantumbadger.redreader.common.PinnedSubredditFeed
import org.quantumbadger.redreader.reddit.things.SubredditCanonicalId
import org.quantumbadger.redreader.reddit.url.SubredditPostListURL
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PinnedSubredditFeedTest {

	private fun feed(vararg names: String) =
		PinnedSubredditFeed.from(names.map(::SubredditCanonicalId))

	@Test
	fun emptyAndIneligiblePinsDoNotCreateAListing() {
		assertTrue(feed().isEmpty)
		assertTrue(feed("/user/example", "all", "popular", "all-android", "u_example").isEmpty)
		assertThrows(IllegalStateException::class.java) { feed().url }
	}

	@Test
	fun profilesAndSpecialDestinationsAreExcludedFromMixedPins() {
		val result = feed("/user/example", "all", "android", "popular", "java")
		assertFalse(result.isEmpty)
		assertFalse(result.isTooLarge)
		assertEquals("android+java", result.url.subreddit)
	}

	@Test
	fun combinedPinsAreFlattenedDeduplicatedAndSorted() {
		val result = feed("Java+ANDROID", "android", "kotlin", "java")
		assertEquals("android+java+kotlin", result.url.subreddit)
		assertEquals(SubredditPostListURL.Type.SUBREDDIT_COMBINATION, result.url.type)
	}

	@Test
	fun invalidCombinationPinsAreNotPartiallyInterpreted() {
		assertEquals("java", feed("android++kotlin", "all+android", "java").url.subreddit)
	}

	@Test
	fun singleAndCombinedListingsRoundTripWithoutPreferences() {
		for (result in listOf(feed("android"), feed("android", "java"))) {
			val url = result.url
			val parsed = SubredditPostListURL.parse(url.generateJsonUri())
			assertEquals(url.type, parsed.type)
			assertEquals(url.subreddit, parsed.subreddit)
			assertEquals(url.generateJsonUri(), parsed.generateJsonUri())
		}
	}

	@Test
	fun aFeedRetainsItsSnapshotWhenPinsAreChanged() {
		val pins = mutableListOf(SubredditCanonicalId("java"), SubredditCanonicalId("android"))
		val result = PinnedSubredditFeed.from(pins)
		pins.clear()
		assertEquals("android+java", result.url.subreddit)
		assertTrue(PinnedSubredditFeed.from(pins).isEmpty)
	}

	@Test
	fun componentBoundRejectsWholeFeedRatherThanTruncating() {
		val atLimit = (0 until 100).map { SubredditCanonicalId("r$it") }
		assertFalse(PinnedSubredditFeed.from(atLimit).isTooLarge)
		val overLimit = PinnedSubredditFeed.from(atLimit + SubredditCanonicalId("extra"))
		assertTrue(overLimit.isTooLarge)
		assertFalse(overLimit.isEmpty)
		assertThrows(IllegalStateException::class.java) { overLimit.url }
	}

	@Test
	fun characterBoundRejectsWholeFeedEvenBelowComponentBound() {
		val result = PinnedSubredditFeed.from(
			(0 until 90).map { SubredditCanonicalId("longsubredditname$it") }
		)
		assertTrue(result.isTooLarge)
		assertThrows(IllegalStateException::class.java) { result.url }
	}
}

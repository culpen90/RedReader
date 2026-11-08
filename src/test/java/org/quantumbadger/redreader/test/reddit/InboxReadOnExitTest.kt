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

package org.quantumbadger.redreader.test.reddit

import org.junit.Assert.*
import org.junit.Test
import org.quantumbadger.redreader.account.RedditAccount
import org.quantumbadger.redreader.reddit.InboxReadOnExit
import org.quantumbadger.redreader.reddit.kthings.RedditIdAndType

class InboxReadOnExitTest {

	private val user = RedditAccount("Alice", null, 1, null)
	private fun id(value: String) = RedditIdAndType(value)

	@Test
	fun disabledVisitNeverMarksAnything() {
		val visit = InboxReadOnExit(user, null)
		visit.onVisible(id("t4_seen"), true)
		assertTrue(visit.finish(false, true) { false }.isEmpty())
	}

	@Test
	fun sentModeNeverSubmitsInboxReadMutations() {
		val visit = InboxReadOnExit(user, null)
		visit.onVisible(id("t4_seen"), true)
		assertTrue(visit.finish(true, false) { false }.isEmpty())
	}

	@Test
	fun anonymousVisitCannotSubmitReadMutations() {
		val visit = InboxReadOnExit(RedditAccount("", null, 1, null), null)
		visit.onVisible(id("t4_seen"), true)
		assertTrue(visit.finish(true, true) { false }.isEmpty())
	}

	@Test
	fun onlyVisibleExplicitlyUnreadSupportedItemsAreIncluded() {
		val visit = InboxReadOnExit(user, null)
		visit.onVisible(id("t4_seen"), true)
		visit.onVisible(id("t1_reply"), true)
		visit.onVisible(id("t4_read"), false)
		visit.onVisible(id("t3_post"), true)
		visit.onVisible(id("t4_bad,id"), true)
		assertEquals(listOf(id("t4_seen"), id("t1_reply")),
			visit.finish(true, true) { false }.flatten())
	}

	@Test
	fun seenIdsAreDeduplicatedAndManualReadsAreExcluded() {
		val visit = InboxReadOnExit(user, null)
		visit.onVisible(id("t4_seen"), true)
		visit.onVisible(id("t4_seen"), true)
		visit.onVisible(id("t1_manual"), true)
		assertEquals(listOf(id("t4_seen")),
			visit.finish(true, true) { it.value == "t1_manual" }.flatten())
	}

	@Test
	fun exitFreezesTheSetAndCannotBeSubmittedTwice() {
		val visit = InboxReadOnExit(user, null)
		visit.onVisible(id("t4_seen"), true)
		val sent = visit.finish(true, true) { false }
		visit.onVisible(id("t4_new"), true)
		assertEquals(listOf(id("t4_seen")), sent.flatten())
		assertTrue(visit.finish(true, true) { false }.isEmpty())
	}

	@Test
	fun restoreKeepsOnlyValidatedViewedIds() {
		val first = InboxReadOnExit(user, null)
		first.onVisible(id("t4_seen"), true)
		val restored = InboxReadOnExit(user, first.savedIds() + listOf("t3_bad", "bad"))
		assertEquals(listOf(id("t4_seen")), restored.finish(true, true) { false }.flatten())
	}

	@Test
	fun moreThanOneBatchHasExactlyTwentyFiveIdsPerRequest() {
		val visit = InboxReadOnExit(user, null)
		for(i in 0 until 63) visit.onVisible(id("t4_m$i"), true)
		val batches = visit.finish(true, true) { false }
		assertEquals(listOf(25, 25, 13), batches.map { it.size })
		assertEquals(63, batches.flatten().distinct().size)
	}
}

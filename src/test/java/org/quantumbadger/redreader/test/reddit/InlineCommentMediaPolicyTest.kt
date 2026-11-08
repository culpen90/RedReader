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

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.quantumbadger.redreader.reddit.prepared.InlineCommentMediaPolicy.isAllowed

class InlineCommentMediaPolicyTest {

	@Test
	fun aKnownSafeParentDoesNotNeedSensitivePreviewOptIns() {
		assertTrue(isAllowed(false, false, null, false, false))
	}

	@Test
	fun aSensitiveParentNeedsTheCorrespondingOptIn() {
		assertFalse(isAllowed(true, false, null, false, true))
		assertTrue(isAllowed(true, false, null, true, false))
		assertFalse(isAllowed(false, true, null, true, false))
		assertTrue(isAllowed(false, true, null, false, true))
	}

	@Test
	fun aProfileCannotAssumeUnknownSpoilerOrNsfwFlagsAreSafe() {
		assertFalse(isAllowed(null, null, false, false, false))
		assertTrue(isAllowed(null, null, false, false, true))
		assertFalse(isAllowed(null, null, null, false, true))
		assertFalse(isAllowed(null, null, null, true, false))
		assertTrue(isAllowed(null, null, null, true, true))
	}

	@Test
	fun aCommentFlagCannotWeakenAParentFlagOrViceVersa() {
		assertFalse(isAllowed(true, false, false, false, true))
		assertFalse(isAllowed(false, false, true, false, true))
	}
}

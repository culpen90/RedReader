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

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.quantumbadger.redreader.R
import org.quantumbadger.redreader.common.General
import org.quantumbadger.redreader.common.PrefsUtility
import org.quantumbadger.redreader.common.SharedPrefsWrapper
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class MinimumCommentsPreferenceTest {

	private val app = RuntimeEnvironment.getApplication()
	private lateinit var prefs: SharedPreferences
	private val thresholdKey get() = app.getString(R.string.pref_behaviour_min_comments_key)

	@Before
	fun setUpPreferences() {
		ReflectionHelpers.getStaticField<AtomicReference<SharedPrefsWrapper>>(
			General::class.java, "mPrefs"
		).set(null)
		val wrapped = General.getSharedPrefs(app)
		prefs = ReflectionHelpers.getField(wrapped, "mPrefs")
		prefs.edit().clear().commit()
		ReflectionHelpers.setStaticField(PrefsUtility::class.java, "sharedPrefs", wrapped)
		ReflectionHelpers.setStaticField(PrefsUtility::class.java, "mRes", app.resources)
	}

	@Test
	fun filterDefaultsOffWithTenCommentThreshold() {
		assertFalse(PrefsUtility.pref_behaviour_hide_few_comments())
		assertEquals(10, PrefsUtility.pref_behaviour_min_comments())
	}

	@Test
	fun thresholdBoundariesRoundTrip() {
		for (value in listOf(0, 1, 10, 100_000)) {
			PrefsUtility.pref_behaviour_min_comments(value)
			assertEquals(value, PrefsUtility.pref_behaviour_min_comments())
		}
	}

	@Test
	fun malformedRestoredValuesUseTheSafeDefault() {
		for (value in listOf("", "abc", "-1", "100001", "999999999999999999999")) {
			prefs.edit().putString(thresholdKey, value).commit()
			assertEquals(value, 10, PrefsUtility.pref_behaviour_min_comments())
		}
		prefs.edit().putInt(thresholdKey, 50).commit()
		assertEquals(10, PrefsUtility.pref_behaviour_min_comments())
	}

	@Test
	fun bothSettingsRefreshExistingListings() {
		assertTrue(PrefsUtility.isRefreshRequired(app, thresholdKey))
		assertTrue(PrefsUtility.isRefreshRequired(
			app, app.getString(R.string.pref_behaviour_hide_few_comments_key)
		))
	}
}

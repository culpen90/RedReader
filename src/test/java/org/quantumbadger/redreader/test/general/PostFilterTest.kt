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

import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.preference.Preference
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.quantumbadger.redreader.R
import org.quantumbadger.redreader.common.General
import org.quantumbadger.redreader.common.PostFilter
import org.quantumbadger.redreader.common.PrefsUtility
import org.quantumbadger.redreader.settings.SettingsActivity
import org.quantumbadger.redreader.settings.SettingsFragment
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.util.concurrent.atomic.AtomicReference
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PostFilterTest {

	private val context get() = RuntimeEnvironment.getApplication()
	private val keys = intArrayOf(
		R.string.pref_blocked_subreddits_key,
		R.string.pref_blocked_subreddit_regexes_key,
		R.string.pref_hidden_post_title_terms_key,
		R.string.pref_hidden_post_title_regexes_key
	)

	@Before
	fun resetPreferences() {
		ReflectionHelpers.getStaticField<AtomicReference<Any?>>(General::class.java, "mPrefs")
			.set(null)
		ReflectionHelpers.setStaticField(PrefsUtility::class.java, "mRes", context.resources)
		ReflectionHelpers.setStaticField(PrefsUtility::class.java, "sharedPrefs",
			General.getSharedPrefs(context))
		ReflectionHelpers.setStaticField(PrefsUtility::class.java, "appContext", context)
		keys.forEach { General.getSharedPrefs(context).edit()
			.putString(context.getString(it), "").apply() }
	}

	@Test
	fun noFiltersKeepAllTitlesAndSubreddits() {
		val filter = PostFilter(emptyList(), emptyList(), emptyList())
		assertFalse(filter.hidesTitle("Any TITLE"))
		assertFalse(filter.hidesTitle(null))
		assertFalse(filter.blocksSubreddit("android"))
	}

	@Test
	fun termsAreTrimmedCaseInsensitiveSubstrings() {
		val filter = PostFilter(listOf("  Über ", "cat"), emptyList(), emptyList())
		assertTrue(filter.hidesTitle("ÜBER the hill"))
		assertTrue(filter.hidesTitle("A catapult"))
		assertFalse(filter.hidesTitle("A dog"))
	}

	@Test
	fun emptyRestoredTermsDoNotHideEverything() {
		val filter = PostFilter(listOf("", " "), listOf(""), listOf(""))
		assertFalse(filter.hidesTitle("News"))
		assertFalse(filter.blocksSubreddit("News"))
	}

	@Test
	fun regexCaseFlagAndAnchorsAreRespected() {
		val sensitive = PostFilter(emptyList(), listOf("^News$"), emptyList())
		assertTrue(sensitive.hidesTitle("News"))
		assertFalse(sensitive.hidesTitle("NEWS"))
		assertFalse(sensitive.hidesTitle("Old News"))
		val insensitive = PostFilter(emptyList(), listOf("(?i)^news$"), emptyList())
		assertTrue(insensitive.hidesTitle("NEWS"))
	}

	@Test
	fun malformedSavedRegexDoesNotDiscardValidFilters() {
		val filter = PostFilter(emptyList(), listOf("[", "sale$"), listOf("[", "^test"))
		assertTrue(filter.hidesTitle("For sale"))
		assertFalse(filter.hidesTitle("Normal"))
		assertTrue(filter.blocksSubreddit("testing"))
		assertFalse(filter.blocksSubreddit("android"))
	}

	@Test
	fun subredditRulesStayIndependentFromTitleRules() {
		val filter = PostFilter(listOf("news"), emptyList(), listOf("(?i)^android$"))
		assertTrue(filter.blocksSubreddit("ANDROID"))
		assertFalse(filter.blocksSubreddit("androiddev"))
		assertFalse(filter.hidesTitle("Android"))
		assertFalse(filter.blocksSubreddit("news"))
	}

	@Test
	fun snapshotDoesNotChangeWhenInputListsChange() {
		val terms = mutableListOf("news")
		val regexes = mutableListOf("sale$")
		val filter = PostFilter(terms, regexes, emptyList())
		terms.clear()
		regexes.clear()
		assertTrue(filter.hidesTitle("News"))
		assertTrue(filter.hidesTitle("For sale"))
	}

	@Test
	fun titleTermPreferencesDeduplicateAndRemove() {
		PrefsUtility.pref_hidden_post_title_term_add("  cats ")
		PrefsUtility.pref_hidden_post_title_term_add("CATS")
		PrefsUtility.pref_hidden_post_title_term_add(" ")
		assertEquals(listOf("cats"), PrefsUtility.pref_hidden_post_title_terms())
		PrefsUtility.pref_hidden_post_title_term_remove("cats")
		assertTrue(PrefsUtility.pref_hidden_post_title_terms().isEmpty())
	}

	@Test
	fun regexPreferencesRoundTripEscapesAndRemove() {
		val expression = "(?:foo|bar)\\d+"
		PrefsUtility.pref_hidden_post_title_regex_add(expression)
		PrefsUtility.pref_blocked_subreddit_regex_add("(?i)^news$")
		assertEquals(listOf(expression), PrefsUtility.pref_hidden_post_title_regexes())
		assertEquals(listOf("(?i)^news$"), PrefsUtility.pref_blocked_subreddit_regexes())
		PrefsUtility.pref_hidden_post_title_regex_remove(expression)
		PrefsUtility.pref_blocked_subreddit_regex_remove("(?i)^news$")
		assertTrue(PrefsUtility.pref_hidden_post_title_regexes().isEmpty())
		assertTrue(PrefsUtility.pref_blocked_subreddit_regexes().isEmpty())
	}

	@Test
	fun invalidRegexIsNotSaved() {
		try {
			PrefsUtility.pref_hidden_post_title_regex_add("[")
			fail("Invalid expression accepted")
		} catch (expected: java.util.regex.PatternSyntaxException) {
			assertTrue(PrefsUtility.pref_hidden_post_title_regexes().isEmpty())
		}
	}

	@Test
	fun allFilterChangesRequestListingRefresh() {
		keys.forEach { assertTrue(PrefsUtility.isRefreshRequired(context, context.getString(it))) }
	}

	private fun findInput(view: View): EditText? {
		if(view is EditText) return view
		if(view is ViewGroup) {
			for(index in 0 until view.childCount) {
				findInput(view.getChildAt(index))?.let { return it }
			}
		}
		return null
	}

	private fun assertInvalidInputRetained(preferenceKey: Int, value: String) {
		val controller = Robolectric.buildActivity(SettingsActivity::class.java).setup()
		try {
			val activity = controller.get()
			activity.onPanelSelected("behaviour")
			activity.supportFragmentManager.executePendingTransactions()
			val fragment = activity.supportFragmentManager
				.findFragmentById(R.id.single_fragment_container) as SettingsFragment
			val preference = fragment.findPreference<Preference>(activity.getString(preferenceKey))!!
			preference.onPreferenceClickListener!!.onPreferenceClick(preference)
			(ShadowDialog.getLatestDialog() as AlertDialog)
				.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
			val dialog = ShadowDialog.getLatestDialog() as AlertDialog
			val input = findInput(dialog.window!!.decorView)!!
			input.setText(value)
			dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
			assertTrue(dialog.isShowing)
			assertEquals(value, input.text.toString())
			assertNotNull(input.error)
			assertSame(dialog, ShadowDialog.getLatestDialog())
			dialog.dismiss()
		} finally {
			controller.pause().stop().destroy()
		}
	}

	@Test
	fun invalidTitleRegexStaysEditable() {
		assertInvalidInputRetained(R.string.pref_behaviour_hidden_post_titles_key, "/[/")
	}

	@Test
	fun blankTitleTermStaysEditable() {
		assertInvalidInputRetained(R.string.pref_behaviour_hidden_post_titles_key, " ")
	}

	@Test
	fun invalidSubredditNameStaysEditable() {
		assertInvalidInputRetained(R.string.pref_behaviour_manage_blocked_subreddits_key, "a b!")
	}
}

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

import android.content.Intent
import android.content.SharedPreferences
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.quantumbadger.redreader.R
import org.quantumbadger.redreader.account.RedditAccountManager
import org.quantumbadger.redreader.activities.HtmlViewActivity
import org.quantumbadger.redreader.common.General
import org.quantumbadger.redreader.common.PrefsUtility
import org.quantumbadger.redreader.common.SharedPrefsWrapper
import org.quantumbadger.redreader.common.time.TimestampUTC
import org.quantumbadger.redreader.reddit.ReactionGifFilter
import org.quantumbadger.redreader.reddit.kthings.RedditComment
import org.quantumbadger.redreader.reddit.kthings.RedditIdAndType
import org.quantumbadger.redreader.reddit.kthings.RedditTimestampUTC
import org.quantumbadger.redreader.reddit.kthings.UrlEncodedString
import org.quantumbadger.redreader.reddit.prepared.RedditChangeDataManager
import org.quantumbadger.redreader.reddit.prepared.RedditParsedComment
import org.quantumbadger.redreader.reddit.prepared.RedditRenderableComment
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ReactionGifCollapseTest {

	private val app get() = RuntimeEnvironment.getApplication()
	private lateinit var prefs: SharedPreferences
	private lateinit var host: ActivityController<HtmlViewActivity>
	private lateinit var changes: RedditChangeDataManager
	private val commentId = RedditIdAndType("t1_comment")

	@Before
	fun setUp() {
		ReflectionHelpers.getStaticField<AtomicReference<SharedPrefsWrapper>>(
			General::class.java, "mPrefs"
		).set(null)
		val wrapped = General.getSharedPrefs(app)
		prefs = ReflectionHelpers.getField(wrapped, "mPrefs")
		prefs.edit().clear().commit()
		ReflectionHelpers.setStaticField(PrefsUtility::class.java, "sharedPrefs", wrapped)
		ReflectionHelpers.setStaticField(PrefsUtility::class.java, "mRes", app.resources)
		ReflectionHelpers.setStaticField(PrefsUtility::class.java, "appContext", app)
		val intent = Intent(app, HtmlViewActivity::class.java)
			.putExtra("html", "<p>Test</p>").putExtra("title", "Test")
		host = Robolectric.buildActivity(HtmlViewActivity::class.java, intent).setup()
		changes = RedditChangeDataManager.getInstance(RedditAccountManager.getAnon())
		changes.markHidden(TimestampUTC.now(), commentId, null)
	}

	@After
	fun tearDown() {
		host.pause().stop().destroy()
	}

	private fun enableFilter() {
		prefs.edit().putBoolean(
			app.getString(R.string.pref_behaviour_collapse_reaction_gifs_key), true
		).commit()
	}

	private fun comment(
		body: String = "![gif](giphy|abc)",
		currentUser: String = "reader",
		neverAutoCollapse: Boolean = false
	): RedditRenderableComment {
		val raw = RedditComment(
			id = "comment", name = commentId, author = UrlEncodedString("author"),
			body = UrlEncodedString(body), body_html = UrlEncodedString("<p>Test</p>"),
			created_utc = RedditTimestampUTC(TimestampUTC.fromUtcSecs(1700000000))
		)
		val result = RedditRenderableComment(
			RedditParsedComment(raw, host.get()), null, null, currentUser,
			true, false, neverAutoCollapse
		)
		result.setReactionGif(ReactionGifFilter.matches(PrefsUtility.reactionGifFilter(), raw.body))
		return result
	}

	@Test
	fun filterIsOffByDefaultAndDefaultDomainsAreAvailable() {
		assertFalse(PrefsUtility.pref_behaviour_collapse_reaction_gifs())
		assertNull(PrefsUtility.reactionGifFilter())
		assertEquals(ReactionGifFilter.DEFAULT_DOMAINS,
			PrefsUtility.pref_behaviour_reaction_gif_domains())
		assertFalse(comment().isCollapsed(changes))
	}

	@Test
	fun enabledFilterCollapsesMatchingComment() {
		enableFilter()
		assertTrue(comment().isCollapsed(changes))
		assertFalse(comment("A regular comment").isCollapsed(changes))
	}

	@Test
	fun manuallyExpandedCommentOverridesAutomaticCollapse() {
		enableFilter()
		val comment = comment()
		assertTrue(comment.isCollapsed(changes))
		changes.markHidden(TimestampUTC.now(), commentId, false)
		assertFalse(comment.isCollapsed(changes))
	}

	@Test
	fun userCommentsAndNeverAutoCollapseListingsKeepTheirExemptions() {
		enableFilter()
		assertFalse(comment(currentUser = "author").isCollapsed(changes))
		assertFalse(comment(neverAutoCollapse = true).isCollapsed(changes))
	}

	@Test
	fun customDomainSettingReplacesDomainHeuristics() {
		enableFilter()
		prefs.edit().putString(
			app.getString(R.string.pref_behaviour_reaction_gif_domains_key), "custom.example"
		).commit()
		assertTrue(comment("https://custom.example/reaction").isCollapsed(changes))
		assertFalse(comment("https://giphy.com/gifs/abc").isCollapsed(changes))
	}

	@Test
	fun bothSettingsRequestListingRefresh() {
		assertTrue(PrefsUtility.isRefreshRequired(app,
			app.getString(R.string.pref_behaviour_collapse_reaction_gifs_key)))
		assertTrue(PrefsUtility.isRefreshRequired(app,
			app.getString(R.string.pref_behaviour_reaction_gif_domains_key)))
	}
}

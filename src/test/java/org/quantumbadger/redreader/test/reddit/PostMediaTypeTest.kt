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

import android.content.SharedPreferences
import androidx.appcompat.view.menu.MenuBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.quantumbadger.redreader.R
import org.quantumbadger.redreader.activities.MainActivity
import org.quantumbadger.redreader.activities.OptionsMenuUtility
import org.quantumbadger.redreader.common.General
import org.quantumbadger.redreader.common.PrefsUtility
import org.quantumbadger.redreader.common.SharedPrefsWrapper
import org.quantumbadger.redreader.common.time.TimestampUTC
import org.quantumbadger.redreader.reddit.kthings.RedditIdAndType
import org.quantumbadger.redreader.reddit.kthings.RedditPost
import org.quantumbadger.redreader.reddit.kthings.RedditTimestampUTC
import org.quantumbadger.redreader.reddit.kthings.UrlEncodedString
import org.quantumbadger.redreader.reddit.prepared.PostMediaType
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PostMediaTypeTest {

	private val app = RuntimeEnvironment.getApplication()
	private lateinit var prefs: SharedPreferences

	@Before
	fun setUpPreferences() {
		app.setTheme(R.style.RR_Dark)
		ReflectionHelpers.getStaticField<AtomicReference<SharedPrefsWrapper>>(
			General::class.java, "mPrefs"
		).set(null)
		val wrapped = General.getSharedPrefs(app)
		prefs = ReflectionHelpers.getField(wrapped, "mPrefs")
		prefs.edit().clear().commit()
		ReflectionHelpers.setStaticField(PrefsUtility::class.java, "sharedPrefs", wrapped)
		ReflectionHelpers.setStaticField(PrefsUtility::class.java, "mRes", app.resources)
		ReflectionHelpers.setStaticField(PrefsUtility::class.java, "appContext", app)
	}

	private fun post(url: String) = RedditPost(
		id = "abcd", name = RedditIdAndType("t3_abcd"), url = UrlEncodedString(url),
		subreddit = UrlEncodedString("test"), num_comments = 10, score = 1,
		created_utc = RedditTimestampUTC(TimestampUTC.fromUtcSecs(0)),
		permalink = UrlEncodedString("/r/test/comments/abcd/test/")
	)

	@Test
	fun directMediaIgnoresQueryAndFragmentAndUsesDestinationOverrides() {
		assertEquals(PostMediaType.IMAGE, PostMediaType.forPost(post("https://example.com/a.JPG?q=x#z")))
		assertEquals(PostMediaType.VIDEO, PostMediaType.forPost(post("https://example.com/a.mp4?q=x#z")))
		assertEquals(PostMediaType.VIDEO, PostMediaType.forPost(post("https://i.imgur.com/a.mp4")))
		assertEquals(PostMediaType.IMAGE, PostMediaType.forPost(post("https://example.com/page").copy(
			url_overridden_by_dest = UrlEncodedString("https://example.com/a.webp#image")
		)))
		assertEquals(PostMediaType.LINK, PostMediaType.forPost(post("https://example.com/?a=cat.jpg")))
	}

	@Test
	fun nativeAndHostedMediaAreClassifiedWithoutPreviewMetadata() {
		assertEquals(PostMediaType.VIDEO, PostMediaType.forPost(post("https://v.redd.it/abcd")))
		assertEquals(PostMediaType.VIDEO, PostMediaType.forPost(post("https://example.com/page").copy(is_video = true)))
		assertEquals(PostMediaType.GALLERY, PostMediaType.forPost(post("https://www.imgur.com/a/abcd")))
		assertEquals(PostMediaType.IMAGE, PostMediaType.forPost(post("https://i.redd.it/abcd")))
		assertEquals(PostMediaType.SELF, PostMediaType.forPost(post("https://reddit.com").copy(is_self = true)))
		assertEquals(PostMediaType.CROSSPOST, PostMediaType.forPost(post("https://i.redd.it/a.jpg").copy(crosspost_parent = "t3_xyz")))
	}

	@Test
	fun filtersDefaultOnAndChangesRequestARefresh() {
		for (type in PostMediaType.values()) {
			assertTrue(PrefsUtility.pref_media_type(type))
			PrefsUtility.pref_media_type_set(type, false)
			assertFalse(PrefsUtility.pref_media_type(type))
			assertTrue(PrefsUtility.isRefreshRequired(app, "pref_media_type_" + type.preferenceValue))
		}
	}

	@Test
	fun mediaMenuRemainsAvailableWhenSortIsHiddenAndForSearchAndSavedListings() {
		prefs.edit()
			.putString(app.getString(R.string.pref_menus_appbar_sort_key), "-1")
			.putString(app.getString(R.string.pref_menus_appbar_accounts_key), "-1")
			.commit()
		val activity = Robolectric.buildActivity(MainActivity::class.java).get()
		for ((search, sortable) in listOf(false to true, true to true, false to false)) {
			val menu = MenuBuilder(activity)
			OptionsMenuUtility.prepare(
				activity, menu, false, true, false, search, false, false,
				sortable, false, false, null, false, false, null, null
			)
			val mediaMenu = (0 until menu.size()).map { menu.getItem(it) }
				.firstOrNull { it.title == app.getString(R.string.options_media_type) }?.subMenu
			assertNotNull(mediaMenu)
			assertEquals(PostMediaType.values().size, mediaMenu!!.size())
		}
	}
}

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

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.NetworkInfo
import android.os.SystemClock
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.quantumbadger.redreader.R
import org.quantumbadger.redreader.activities.ChangelogActivity
import org.quantumbadger.redreader.account.RedditAccountManager
import org.quantumbadger.redreader.cache.CacheRequest
import org.quantumbadger.redreader.cache.downloadstrategy.DownloadStrategyIfNotCached
import org.quantumbadger.redreader.common.Constants
import org.quantumbadger.redreader.common.Priority
import org.quantumbadger.redreader.common.General
import org.quantumbadger.redreader.common.PrefsUtility
import org.quantumbadger.redreader.common.SharedPrefsWrapper
import org.quantumbadger.redreader.common.UriString
import org.quantumbadger.redreader.reddit.prepared.InlineMedia
import org.quantumbadger.redreader.reddit.prepared.InlinePreviewLoader
import org.quantumbadger.redreader.reddit.prepared.bodytext.BodyElementInlineMedia
import org.quantumbadger.redreader.views.InlineMediaView
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowNetworkInfo
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class InlineMediaViewportTest {

	private val app = RuntimeEnvironment.getApplication()
	private lateinit var prefs: SharedPreferences

	@Before
	fun setUpPreferences() {
		ReflectionHelpers.getStaticField<AtomicReference<SharedPrefsWrapper>>(
			General::class.java, "mPrefs"
		).set(null)
		val wrapped = General.getSharedPrefs(app)
		prefs = ReflectionHelpers.getField(wrapped, "mPrefs")
		prefs.edit().clear()
			.putString(app.getString(R.string.pref_images_inline_comment_media_key), "always")
			.commit()
		ReflectionHelpers.setStaticField(PrefsUtility::class.java, "sharedPrefs", wrapped)
		ReflectionHelpers.setStaticField(PrefsUtility::class.java, "mRes", app.resources)
		ReflectionHelpers.setStaticField(PrefsUtility::class.java, "appContext", app)
	}

	private data class Entry(val view: InlineMediaView, val loader: InlinePreviewLoader)

	private fun entry(activity: ChangelogActivity): Entry {
		val url = UriString("https://example.invalid/test.png")
		val media = InlineMedia(
			InlineMedia.Type.IMAGE, 100, 100,
			listOf(InlineMedia.Still(url, 100, 100))
		)
		val element = BodyElementInlineMedia(url, media, null)
		// Null details prevent requests: the test observes listener ownership/bitmap release,
		// independently of cache/network callbacks.
		val loader = InlinePreviewLoader(activity) { null }
		ReflectionHelpers.setField(element, "mLoader", loader)
		return Entry(InlineMediaView(activity, element), loader)
	}

	private fun pendingRequest(activity: ChangelogActivity, item: Entry): CacheRequest {
		// Do not submit it: this tests cancellation of a request owned by the loader,
		// without starting any network or cache background work.
		val request = CacheRequest(
			UriString("https://example.invalid/test.png"), RedditAccountManager.getAnon(),
			null, Priority(Constants.Priority.INLINE_IMAGE_PREVIEW), DownloadStrategyIfNotCached.INSTANCE,
			Constants.FileType.INLINE_IMAGE_PREVIEW, CacheRequest.DownloadQueueType.IMMEDIATE,
			activity
		) {}
		ReflectionHelpers.setField(item.loader, "mRequest", request)
		ReflectionHelpers.setField(item.loader, "mState", InlinePreviewLoader.State.LOADING)
		return request
	}

	private fun setWifiConnected(connected: Boolean) {
		val connectivity = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
		shadowOf(connectivity).setNetworkInfo(ConnectivityManager.TYPE_WIFI,
			ShadowNetworkInfo.newInstance(
				if (connected) NetworkInfo.DetailedState.CONNECTED else NetworkInfo.DetailedState.DISCONNECTED,
				ConnectivityManager.TYPE_WIFI, 0, true, connected
			)
		)
	}

	private fun listening(entry: Entry): Boolean =
		ReflectionHelpers.getField<InlinePreviewLoader.Listener?>(entry.loader, "mListener") != null

	private fun layout(scroll: ScrollView) {
		scroll.measure(
			View.MeasureSpec.makeMeasureSpec(300, View.MeasureSpec.EXACTLY),
			View.MeasureSpec.makeMeasureSpec(300, View.MeasureSpec.EXACTLY)
		)
		scroll.layout(0, 0, 300, 300)
		scroll.viewTreeObserver.dispatchOnPreDraw()
	}

	@Test
	fun aTallCommentOnlyActivatesImagesIntersectingTheViewport() {
		val controller = Robolectric.buildActivity(ChangelogActivity::class.java).setup().visible()
		try {
			val activity = controller.get()
			val body = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
			val entries = (1..40).map { entry(activity) }
			entries.forEach { body.addView(it.view) }
			val scroll = ScrollView(activity).apply { addView(body) }
			activity.setContentView(scroll)
			layout(scroll)
			val firstVisible = entries.filter(::listening).toSet()
			assertTrue(firstVisible.isNotEmpty())
			assertTrue("Attachment count must not activate the whole row", firstVisible.size <= 4)
			assertFalse(listening(entries.last()))

			scroll.scrollTo(0, 1500)
			scroll.viewTreeObserver.dispatchOnPreDraw()
			val laterVisible = entries.filter(::listening).toSet()
			assertTrue(laterVisible.isNotEmpty())
			assertTrue(laterVisible.size <= 4)
			assertTrue(firstVisible.intersect(laterVisible).isEmpty())
		} finally {
			controller.pause().stop().destroy()
		}
	}

	@Test
	fun detachingTheCommentReleasesListenerAndBothBitmapReferences() {
		val controller = Robolectric.buildActivity(ChangelogActivity::class.java).setup().visible()
		try {
			val activity = controller.get()
			val item = entry(activity)
			val scroll = ScrollView(activity).apply { addView(item.view) }
			activity.setContentView(scroll)
			layout(scroll)
			assertTrue(listening(item))
			val bitmap = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888)
			ReflectionHelpers.setField(item.loader, "mState", InlinePreviewLoader.State.LOADED)
			ReflectionHelpers.setField(item.loader, "mBitmap", bitmap)
			item.view.onInlinePreviewStateChanged(item.loader)

			scroll.removeAllViews()
			assertFalse(listening(item))
			assertNull(item.loader.bitmap)
			val image: ImageView = ReflectionHelpers.getField(item.view, "mImageView")
			assertNull(image.drawable)
			assertEquals(InlinePreviewLoader.State.NOT_LOADED, item.loader.state)
		} finally {
			controller.pause().stop().destroy()
		}
	}

	@Test
	fun aFreshVisibleImageRespectsTheCurrentNetworkPolicy() {
		val controller = Robolectric.buildActivity(ChangelogActivity::class.java).setup().visible()
		try {
			val activity = controller.get()
			val item = entry(activity)
			val scroll = ScrollView(activity).apply { addView(item.view) }
			prefs.edit().putString(
				app.getString(R.string.pref_images_inline_comment_media_key), "never"
			).commit()
			activity.setContentView(scroll)
			layout(scroll)
			assertFalse(listening(item))
			setWifiConnected(false)
			prefs.edit().putString(
				app.getString(R.string.pref_images_inline_comment_media_key), "wifionly"
			).commit()
			SystemClock.sleep(1001)
			scroll.viewTreeObserver.dispatchOnPreDraw()
			assertFalse(listening(item))
		} finally {
			controller.pause().stop().destroy()
		}
	}
	@Test
	fun anActiveImageIsReleasedWhenItsPolicyIsDisabled() {
		val controller = Robolectric.buildActivity(ChangelogActivity::class.java).setup().visible()
		try {
			val activity = controller.get()
			val item = entry(activity)
			val scroll = ScrollView(activity).apply { addView(item.view) }
			activity.setContentView(scroll)
			layout(scroll)
			assertTrue(listening(item))
			val request = pendingRequest(activity, item)
			ReflectionHelpers.setField(item.loader, "mBitmap",
				Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888))
			prefs.edit().putString(
				app.getString(R.string.pref_images_inline_comment_media_key), "never"
			).commit()
			SystemClock.sleep(1001)
			scroll.viewTreeObserver.dispatchOnPreDraw()
			assertFalse(listening(item))
			assertNull(item.loader.bitmap)
			assertTrue(ReflectionHelpers.getField(request, "cancelled"))
			assertEquals(InlinePreviewLoader.State.NOT_LOADED, item.loader.state)
		} finally {
			controller.pause().stop().destroy()
		}
	}

	@Test
	fun anActiveWifiOnlyImageIsCancelledAfterWifiDisconnects() {
		val controller = Robolectric.buildActivity(ChangelogActivity::class.java).setup().visible()
		try {
			val activity = controller.get()
			setWifiConnected(true)
			prefs.edit().putString(
				app.getString(R.string.pref_images_inline_comment_media_key), "wifionly"
			).commit()
			val item = entry(activity)
			val scroll = ScrollView(activity).apply { addView(item.view) }
			activity.setContentView(scroll)
			layout(scroll)
			assertTrue(listening(item))
			val request = pendingRequest(activity, item)
			setWifiConnected(false)
			SystemClock.sleep(1001)
			scroll.viewTreeObserver.dispatchOnPreDraw()
			assertFalse(listening(item))
			assertTrue(ReflectionHelpers.getField(request, "cancelled"))
		} finally {
			controller.pause().stop().destroy()
		}
	}

}

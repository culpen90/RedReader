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
import java.io.ByteArrayOutputStream
import org.quantumbadger.redreader.common.datastream.MemoryDataStream
import org.quantumbadger.redreader.image.ScaledBitmapDecoder
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.NetworkInfo
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.LinearLayoutManager
import org.quantumbadger.redreader.adapters.PostListingManager
import org.quantumbadger.redreader.reddit.RedditPostListItem
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.quantumbadger.redreader.R
import org.quantumbadger.redreader.account.RedditAccountManager
import org.quantumbadger.redreader.activities.ChangelogActivity
import org.quantumbadger.redreader.cache.CacheManager
import org.quantumbadger.redreader.cache.CacheRequest
import org.quantumbadger.redreader.cache.downloadstrategy.DownloadStrategyIfNotCached
import org.quantumbadger.redreader.common.Constants
import org.quantumbadger.redreader.common.General
import org.quantumbadger.redreader.common.PrefsUtility
import org.quantumbadger.redreader.common.Priority
import org.quantumbadger.redreader.common.SharedPrefsWrapper
import org.quantumbadger.redreader.common.UriString
import org.quantumbadger.redreader.common.time.TimestampUTC
import org.quantumbadger.redreader.reddit.kthings.*
import org.quantumbadger.redreader.reddit.prepared.InlinePreviewLoader
import org.quantumbadger.redreader.reddit.prepared.PostPreviewPolicy
import org.quantumbadger.redreader.reddit.prepared.RedditParsedPost
import org.quantumbadger.redreader.reddit.prepared.RedditPreparedPost
import org.quantumbadger.redreader.settings.types.PostLayout
import org.quantumbadger.redreader.settings.SettingsActivity
import org.quantumbadger.redreader.settings.SettingsFragment
import androidx.preference.ListPreference
import org.quantumbadger.redreader.views.PostHeaderPreviewView
import org.quantumbadger.redreader.views.RedditPostHeaderView
import org.quantumbadger.redreader.views.RedditPostView
import org.quantumbadger.redreader.views.InlinePreviewHolderView
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetworkInfo
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PostLayoutTest {

	private val app get() = RuntimeEnvironment.getApplication()
	private lateinit var prefs: SharedPreferences

	@Before
	fun initializePreferences() {
		app.setTheme(R.style.RR_Dark)
		ReflectionHelpers.getStaticField<AtomicReference<SharedPrefsWrapper>>(
			General::class.java, "mPrefs"
		).set(null)
		val wrapped = General.getSharedPrefs(app)
		prefs = ReflectionHelpers.getField(wrapped, "mPrefs")
		prefs.edit().clear().putString(
			app.getString(R.string.pref_images_inline_image_previews_key), "always"
		).commit()
		ReflectionHelpers.setStaticField(PrefsUtility::class.java, "sharedPrefs", wrapped)
		ReflectionHelpers.setStaticField(PrefsUtility::class.java, "mRes", app.resources)
		ReflectionHelpers.setStaticField(PrefsUtility::class.java, "appContext", app)
	}

	private fun mode(layout: PostLayout) {
		prefs.edit().putString(app.getString(R.string.pref_layout_mode_key),
			layout.preferenceValue).commit()
	}

	private fun post(id: String = "abcd", enabled: Boolean = true): RedditPost = RedditPost(
		id = id, name = RedditIdAndType("t3_" + id),
		url = UrlEncodedString("https://example.invalid/image.jpg"),
		title = UrlEncodedString("Post " + id), domain = UrlEncodedString("example.invalid"),
		subreddit = UrlEncodedString("test"), num_comments = 3, score = 1,
		created_utc = RedditTimestampUTC(TimestampUTC.fromUtcSecs(0)),
		permalink = UrlEncodedString("/r/test/comments/" + id),
		preview = RedditPost.Preview(enabled, listOf(RedditPost.Preview.Image(
			source = RedditPost.Preview.ImageDetails(UrlEncodedString("https://example.invalid/full.jpg"), 1200, 600),
			resolutions = listOf(320, 720, 1080).map {
				RedditPost.Preview.ImageDetails(UrlEncodedString("https://example.invalid/" + it + ".jpg"), it, it / 2)
			},
			variants = RedditPost.Preview.ImageVariants()
		)))
	)

	private fun prepare(activity: ChangelogActivity, raw: RedditPost, capturedPermission: Boolean = true)
			= RedditPreparedPost(activity, CacheManager.getInstance(activity), 0,
				RedditParsedPost(activity, raw, false), TimestampUTC.fromUtcSecs(0),
				false, false, false, capturedPermission)

	private fun withActivity(check: (ChangelogActivity) -> Unit) {
		val controller = Robolectric.buildActivity(ChangelogActivity::class.java).setup().visible()
		try { check(controller.get()) } finally { controller.pause().stop().destroy() }
	}

	private fun loaded(activity: ChangelogActivity, post: RedditPreparedPost): InlinePreviewLoader {
		val loader = InlinePreviewLoader(activity) {
			InlinePreviewLoader.calculatePreviewDetails(it, post)
		}
		ReflectionHelpers.setField(loader, "mState", InlinePreviewLoader.State.LOADED)
		ReflectionHelpers.setField(loader, "mBitmap", Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888))
		ReflectionHelpers.setField(loader, "mLoadedForWidthPx", Int.MAX_VALUE)
		ReflectionHelpers.setField(loader, "mLoadedForHeightPx", Int.MAX_VALUE)
		ReflectionHelpers.setField(post, "mInlinePreviewLoader", loader)
		return loader
	}

	private fun pending(activity: ChangelogActivity, loader: InlinePreviewLoader): CacheRequest {
		val request = CacheRequest(UriString("https://example.invalid/test.png"),
			RedditAccountManager.getAnon(), null, Priority(Constants.Priority.INLINE_IMAGE_PREVIEW),
			DownloadStrategyIfNotCached.INSTANCE, Constants.FileType.INLINE_IMAGE_PREVIEW,
			CacheRequest.DownloadQueueType.IMMEDIATE, activity) {}
		ReflectionHelpers.setField(loader, "mRequest", request)
		return request
	}

	private fun wifi(connected: Boolean) {
		val connectivity = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
		shadowOf(connectivity).setNetworkInfo(ConnectivityManager.TYPE_WIFI,
			ShadowNetworkInfo.newInstance(if (connected) NetworkInfo.DetailedState.CONNECTED
				else NetworkInfo.DetailedState.DISCONNECTED, ConnectivityManager.TYPE_WIFI, 0, true, connected))
	}

	@Test
	fun defaultAndCorruptLayoutPreferencesKeepTheExistingLayout() {
		assertEquals(PostLayout.THUMBNAILS, PrefsUtility.post_layout())
		val key = app.getString(R.string.pref_layout_mode_key)
		prefs.edit().putString(key, "not-a-layout").commit()
		assertEquals(PostLayout.THUMBNAILS, PrefsUtility.post_layout())
		prefs.edit().putInt(key, 7).commit()
		assertEquals(PostLayout.THUMBNAILS, PrefsUtility.post_layout())
		assertTrue(PrefsUtility.isRefreshRequired(app, key))
	}

	@Test
	fun restoredInvalidLayoutValuesAreNormalizedBeforeTheActualSettingsScreenInflates() {
		val key = app.getString(R.string.pref_layout_mode_key)
		for (invalid in listOf<Any>(7, "unrecognized-mode")) {
			if (invalid is Int) prefs.edit().putInt(key, invalid).commit()
			else prefs.edit().putString(key, invalid as String).commit()
			val controller = Robolectric.buildActivity(SettingsActivity::class.java).setup().visible()
			try {
				val activity = controller.get()
				activity.onPanelSelected("images_video")
				activity.supportFragmentManager.executePendingTransactions()
				val fragment = activity.supportFragmentManager.findFragmentById(
					R.id.single_fragment_container) as SettingsFragment
				val preference = fragment.findPreference<ListPreference>(key)!!
				assertEquals("thumbnails", preference.value)
				assertEquals("thumbnails", prefs.getString(key, null))
				assertNotNull(preference.entry)
				assertTrue(preference.summary.toString().contains(preference.entry.toString()))
				preference.value = "card"
				assertEquals(PostLayout.CARD, PrefsUtility.post_layout())
				assertTrue(preference.summary.toString().contains(preference.entry.toString()))
			} finally { controller.pause().stop().destroy() }
		}
	}

	@Test
	fun allModesRespectNetworkAndSensitivePermissionsBeforeCreatingALoader() = withActivity { activity ->
		for (layout in PostLayout.values()) {
			mode(layout)
			prefs.edit().putString(app.getString(R.string.pref_images_inline_image_previews_key), "never").commit()
			val blocked = prepare(activity, post())
			assertFalse(blocked.shouldShowInlinePreview())
			assertNull(blocked.getInlinePreviewLoader(activity))
			assertNull(ReflectionHelpers.getField<InlinePreviewLoader?>(blocked, "mInlinePreviewLoader"))
			prefs.edit().putString(app.getString(R.string.pref_images_inline_image_previews_key), "always").commit()
			assertFalse(prepare(activity, post().copy(over_18 = true)).shouldShowInlinePreview())
			assertFalse(prepare(activity, post().copy(spoiler = true)).shouldShowInlinePreview())
			assertFalse(prepare(activity, post(), false).shouldShowInlinePreview())
		}
	}

	@Test
	fun largeLayoutsUseAvailablePreviewsWithoutOverridingTheNetworkSetting() = withActivity { activity ->
		for (layout in PostLayout.values()) {
			mode(layout)
			assertEquals(layout.usesLargePreviews(), prepare(activity, post(enabled = false)).shouldShowInlinePreview())
		}
		mode(PostLayout.CARD)
		prefs.edit().putString(app.getString(R.string.pref_images_inline_image_previews_key), "wifionly").commit()
		wifi(false)
		assertFalse(prepare(activity, post()).shouldShowInlinePreview())
		wifi(true)
		assertTrue(prepare(activity, post()).shouldShowInlinePreview())
	}

	@Test
	fun consentForOneSensitiveCategoryDoesNotEnableTheOther() {
		assertFalse(PostPreviewPolicy.isAllowed(false, true, true, true, true))
		assertFalse(PostPreviewPolicy.isAllowed(true, true, true, true, false))
		assertFalse(PostPreviewPolicy.isAllowed(true, true, true, false, true))
		assertTrue(PostPreviewPolicy.isAllowed(true, true, true, true, true))
		assertTrue(PostPreviewPolicy.isAllowed(true, false, false, false, false))
	}

	@Test
	fun cardOrderKeepsTheImageAboveTheSubtitleAndCommentsControls() = withActivity { activity ->
		for (layout in PostLayout.values()) {
			mode(layout)
			val raw = prepare(activity, post())
			loaded(activity, raw)
			val view = RedditPostView(activity, null, activity, false)
			view.reset(raw)
			val title = view.findViewById<View>(R.id.reddit_post_title)
			val preview = view.findViewById<View>(R.id.reddit_post_image_preview_outer)
			val inner = view.findViewById<View>(R.id.reddit_post_layout_inner)
			val outer = view.findViewById<ViewGroup>(R.id.reddit_post_layout_outer)
			assertEquals(View.VISIBLE, preview.visibility)
			assertTrue(view.findViewById<View>(R.id.reddit_post_comments_button).hasOnClickListeners())
			if (layout == PostLayout.CARD) {
				assertSame(outer, title.parent)
				assertTrue(outer.indexOfChild(title) < outer.indexOfChild(preview))
				assertTrue(outer.indexOfChild(preview) < outer.indexOfChild(inner))
			} else {
				assertTrue(outer.indexOfChild(inner) < outer.indexOfChild(preview))
			}
		}
	}

	private fun <T : View> child(view: View, type: Class<T>): T? {
		if (type.isInstance(view)) return type.cast(view)
		if (view is ViewGroup) for (index in 0 until view.childCount)
			child(view.getChildAt(index), type)?.let { return it }
		return null
	}

	@Test
	fun headersHaveOwnedBoundedPreviewsAndNeedNoFooterOrPrompt() = withActivity { activity ->
		for (layout in PostLayout.values()) {
			mode(layout)
			val prepared = prepare(activity, post())
			val loader = loaded(activity, prepared)
			val header = RedditPostHeaderView(activity, prepared)
			val parent = FrameLayout(activity).apply { addView(header) }
			activity.setContentView(parent)
			val preview = child(header, PostHeaderPreviewView::class.java)
			if (layout == PostLayout.THUMBNAILS) {
				assertNull(preview)
			} else {
				assertNotNull(preview)
				assertEquals(View.VISIBLE, preview!!.visibility)
				assertSame(preview, ReflectionHelpers.getField(loader, "mListener"))
				val imageParent = preview.parent as ViewGroup
				assertEquals(if (layout == PostLayout.CARD) 1 else 2, imageParent.indexOfChild(preview))
				val details = InlinePreviewLoader.calculatePreviewDetails(activity, prepared)!!
				assertTrue(details.boxWidthPx <= 1080)
				assertTrue(details.boxHeightPx <= 3000)
				parent.removeAllViews()
				assertNull(loader.bitmap)
				assertNull(ReflectionHelpers.getField<InlinePreviewLoader.Listener?>(loader, "mListener"))
			}
		}
	}

	@Test
	fun recycledRowsCancelOldRequestsAndRejectStaleBitmaps() = withActivity { activity ->
		mode(PostLayout.CARD)
		val first = prepare(activity, post())
		val loader = loaded(activity, first)
		val view = RedditPostView(activity, null, activity, false)
		view.reset(first)
		val request = pending(activity, loader)
		val generation: Int = ReflectionHelpers.getField(loader, "mGeneration")
		val second = prepare(activity, post("efgh"))
		loaded(activity, second)
		view.reset(second)
		assertTrue(ReflectionHelpers.getField(request, "cancelled"))
		assertNull(loader.bitmap)
		val stale = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888)
		ReflectionHelpers.callInstanceMethod<Unit>(loader, "onLoadSucceeded",
			ClassParameter.from(Int::class.javaPrimitiveType!!, generation),
			ClassParameter.from(Bitmap::class.java, stale))
		assertNull(loader.bitmap)
		assertNotSame(stale, second.getInlinePreviewLoader(activity)!!.bitmap)
	}

	@Test
	fun policyRevocationCancelsAnActivePostPreviewAndClearsTheView() = withActivity { activity ->
		mode(PostLayout.CARD)
		val prepared = prepare(activity, post())
		val loader = loaded(activity, prepared)
		val view = RedditPostView(activity, null, activity, false)
		view.reset(prepared)
		val parent = FrameLayout(activity).apply { addView(view) }
		activity.setContentView(parent)
		val request = pending(activity, loader)
		prefs.edit().putString(app.getString(R.string.pref_images_inline_image_previews_key), "never").commit()
		SystemClock.sleep(1001)
		parent.viewTreeObserver.dispatchOnPreDraw()
		assertTrue(ReflectionHelpers.getField(request, "cancelled"))
		assertNull(loader.bitmap)
		assertEquals(View.GONE, view.findViewById<View>(R.id.reddit_post_image_preview_outer).visibility)
		assertNull(view.findViewById<ImageView>(R.id.reddit_post_image_preview_imageview).drawable)
	}

	@Test
	fun detachingAnOldHeaderDoesNotClearAReplacementRowsListener() = withActivity { activity ->
		mode(PostLayout.CARD)
		val prepared = prepare(activity, post())
		val loader = loaded(activity, prepared)
		val header = PostHeaderPreviewView(activity, prepared)
		val parent = FrameLayout(activity).apply { addView(header) }
		activity.setContentView(parent)
		val row = RedditPostView(activity, null, activity, false)
		row.reset(prepared)
		parent.removeView(header)
		assertSame(row, ReflectionHelpers.getField(loader, "mListener"))
		assertNotNull(loader.bitmap)
	}

	@Test
	fun previewHolderBoundsTallImagesInsideATabletPane() = withActivity { activity ->
		val list = RecyclerView(activity)
		list.layout(0, 0, 400, 300)
		val holder = InlinePreviewHolderView(activity)
		list.addView(holder)
		holder.setImageSize(100, 4000)
		holder.measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
			View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
		assertEquals(400, holder.measuredWidth)
		assertEquals(262, holder.measuredHeight)
		holder.setImageSize(50, 100000000)
		holder.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
			View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
		assertEquals(262, holder.measuredHeight)
	}
	@Test
	fun detachedListingsDoNotReactivateTheirOldPreloadWindow() = withActivity { activity ->
		mode(PostLayout.CARD)
		val prepared = prepare(activity, post())
		val loader = loaded(activity, prepared)
		val manager = PostListingManager(activity)
		manager.setLayoutManager(object : LinearLayoutManager(activity) {
			override fun findFirstVisibleItemPosition() = 0
			override fun findLastVisibleItemPosition() = 10
		})
		manager.addPosts(listOf(RedditPostListItem(prepared, null, activity, false)))
		manager.setPreloadEnabled(true)
		assertTrue(ReflectionHelpers.getField(loader, "mActive"))
		val request = pending(activity, loader)
		manager.setPreloadEnabled(false)
		assertTrue(ReflectionHelpers.getField(request, "cancelled"))
		assertNull(loader.bitmap)
		manager.updatePreloadWindow()
		manager.refreshPreloadWindow()
		assertFalse(ReflectionHelpers.getField(loader, "mActive"))
		val reloaded = loaded(activity, prepared)
		manager.setPreloadEnabled(true)
		assertTrue(ReflectionHelpers.getField(reloaded, "mActive"))
		manager.setPreloadEnabled(false)
	}

	@Test
	fun recognizedHostsWithoutImageMetadataKeepTheirFallbackInsteadOfCreatingALoader() = withActivity { activity ->
		for (layout in PostLayout.values()) {
			mode(layout)
			val prepared = prepare(activity, post().copy(domain = UrlEncodedString("i.redd.it"), preview = null))
			assertFalse(prepared.shouldShowInlinePreview())
			assertNull(prepared.getInlinePreviewLoader(activity))
		}
	}

	@Test
	fun sourceOnlyGalleryUsesTheSameHardDecodeBoxAsOrdinaryPreviews() = withActivity { activity ->
		mode(PostLayout.CARD)
		val id = UrlEncodedString("only")
		val raw = post().copy(preview = null,
			gallery_data = RedditPost.GalleryData(listOf(MaybeParseError.Ok(
				RedditPost.GalleryData.GalleryItem(id)))),
			media_metadata = mapOf(id to MaybeParseError.Ok(RedditMediaMetadata(
				"valid", "Image", "image/jpeg", ImageMetadata(24000, 12000,
					UrlEncodedString("https://preview.redd.it/full.jpg")), "only", null))))
		val prepared = prepare(activity, raw)
		assertTrue(prepared.shouldShowInlinePreview())
		val details = InlinePreviewLoader.calculatePreviewDetails(activity, prepared)!!
		assertEquals(24000, details.imageWidthPx)
		assertTrue(details.boxWidthPx <= 1080)
		// Exercise the actual shared decoder with these bounds, without any network.
		val source = Bitmap.createBitmap(2400, 1200, Bitmap.Config.ARGB_8888)
		val bytes = ByteArrayOutputStream()
		assertTrue(source.compress(Bitmap.CompressFormat.PNG, 100, bytes))
		source.recycle()
		MemoryDataStream(bytes.toByteArray()).inputStream.use { stream ->
			val decoded = ScaledBitmapDecoder.decodeToFitWithin(stream,
				details.boxWidthPx, details.boxHeightPx)
			assertTrue(decoded.width <= details.boxWidthPx)
			assertTrue(decoded.height <= details.boxHeightPx)
			decoded.recycle()
		}
	}

	@Test
	fun headerPolicyRevocationCancelsItsRequestAndReleasesTheBitmap() = withActivity { activity ->
		mode(PostLayout.CARD)
		val prepared = prepare(activity, post())
		val loader = loaded(activity, prepared)
		val header = PostHeaderPreviewView(activity, prepared)
		val parent = FrameLayout(activity).apply { addView(header) }
		activity.setContentView(parent)
		val request = pending(activity, loader)
		prefs.edit().putString(app.getString(R.string.pref_images_inline_image_previews_key), "never").commit()
		SystemClock.sleep(1001)
		parent.viewTreeObserver.dispatchOnPreDraw()
		assertTrue(ReflectionHelpers.getField(request, "cancelled"))
		assertNull(loader.bitmap)
		assertEquals(View.GONE, header.visibility)
		assertNull(header.findViewById<ImageView>(R.id.reddit_post_image_preview_imageview).drawable)
	}

	@Test
	fun cardKeepsLeftHandedCommentsAndOptionalCommentsControls() = withActivity { activity ->
		mode(PostLayout.CARD)
		val prepared = prepare(activity, post())
		loaded(activity, prepared)
		val left = RedditPostView(activity, null, activity, true)
		left.reset(prepared)
		val inner = left.findViewById<ViewGroup>(R.id.reddit_post_layout_inner)
		val comments = left.findViewById<View>(R.id.reddit_post_comments_button)
		assertEquals(0, inner.indexOfChild(comments))
		assertTrue(comments.hasOnClickListeners())
		prefs.edit().putBoolean(app.getString(R.string.pref_appearance_post_show_comments_button_key), false).commit()
		val noComments = RedditPostView(activity, null, activity, false)
		noComments.reset(prepared)
		assertNull(noComments.findViewById<View>(R.id.reddit_post_comments_button))
		assertTrue(noComments.findViewById<View>(R.id.reddit_post_layout_outer).hasOnClickListeners())
	}

}

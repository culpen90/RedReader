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
 *
 * You should have received a copy of the GNU General Public License
 * along with RedReader.  If not, see <http://www.gnu.org/licenses/>.
 ******************************************************************************/

package org.quantumbadger.redreader.test.reddit

import android.content.SharedPreferences
import android.graphics.Bitmap
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okio.BufferedSource
import okio.buffer
import okio.source
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.quantumbadger.redreader.R
import org.quantumbadger.redreader.account.RedditAccountManager
import org.quantumbadger.redreader.activities.ChangelogActivity
import org.quantumbadger.redreader.cache.CacheCompressionType
import org.quantumbadger.redreader.cache.CacheDownload
import org.quantumbadger.redreader.cache.CacheManager
import org.quantumbadger.redreader.cache.CacheRequest
import org.quantumbadger.redreader.cache.CacheRequestCallbacks
import org.quantumbadger.redreader.cache.downloadstrategy.DownloadStrategyIfNotCached
import org.quantumbadger.redreader.common.Constants
import org.quantumbadger.redreader.common.General
import org.quantumbadger.redreader.common.GenericFactory
import org.quantumbadger.redreader.common.PrefsUtility
import org.quantumbadger.redreader.common.Priority
import org.quantumbadger.redreader.common.RRError
import org.quantumbadger.redreader.common.SharedPrefsWrapper
import org.quantumbadger.redreader.common.UriString
import org.quantumbadger.redreader.common.datastream.MemoryDataStream
import org.quantumbadger.redreader.common.datastream.SeekableInputStream
import org.quantumbadger.redreader.common.time.TimestampUTC
import org.quantumbadger.redreader.http.HTTPBackend
import org.quantumbadger.redreader.image.ScaledBitmapDecoder
import org.quantumbadger.redreader.reddit.kthings.*
import org.quantumbadger.redreader.reddit.prepared.InlinePreviewLoader
import org.quantumbadger.redreader.reddit.prepared.RedditParsedPost
import org.quantumbadger.redreader.reddit.prepared.RedditPreparedPost
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Exercises the actual cache downloader with intercepted responses, without live networking. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class InlinePreviewTransportTest {

	private val app get() = RuntimeEnvironment.getApplication()
	private lateinit var client: OkHttpClient
	private val backend get() = HTTPBackend.backend
	private val manager get() = CacheManager.getInstance(app)
	private val url get() = UriString("https://example.invalid/" + UUID.randomUUID() + ".png")
	private val cap get() = CacheDownload.MAX_INLINE_PREVIEW_BYTES

	@Before
	fun initializePreferencesAndClient() {
		app.setTheme(R.style.RR_Dark)
		ReflectionHelpers.getStaticField<AtomicReference<SharedPrefsWrapper>>(
			General::class.java, "mPrefs").set(null)
		val wrapped = General.getSharedPrefs(app)
		val prefs: SharedPreferences = ReflectionHelpers.getField(wrapped, "mPrefs")
		prefs.edit().clear()
			.putString(app.getString(R.string.pref_images_inline_image_previews_key), "always")
			.putString(app.getString(R.string.pref_layout_mode_key), "card").commit()
		ReflectionHelpers.setStaticField(PrefsUtility::class.java, "sharedPrefs", wrapped)
		ReflectionHelpers.setStaticField(PrefsUtility::class.java, "mRes", app.resources)
		ReflectionHelpers.setStaticField(PrefsUtility::class.java, "appContext", app)
		client = ReflectionHelpers.getField(backend, "mClient")
	}

	@After
	fun restoreClient() {
		ReflectionHelpers.setField(backend, "mClient", client)
	}

	private fun response(length: Long, input: InputStream) {
		val source = input.source().buffer()
		val intercepted = OkHttpClient.Builder().addInterceptor { chain ->
			Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
				.code(200).message("OK").body(object : ResponseBody() {
					override fun contentType(): MediaType? = null
					override fun contentLength() = length
					override fun source(): BufferedSource = source
				}).build()
		}.build()
		ReflectionHelpers.setField(backend, "mClient", intercepted)
	}

	private class SizedBody(private val length: Int) : InputStream() {
		var readCount = 0
		var closed = false
		override fun read(): Int = if (readCount >= length) -1 else { readCount++; 0 }
		override fun read(bytes: ByteArray, offset: Int, count: Int): Int {
			if (readCount >= length) return -1
			val available = minOf(count, length - readCount)
			bytes.fill(0, offset, offset + available)
			readCount += available
			return available
		}
		override fun close() { closed = true }
	}

	private open class Capture : CacheRequestCallbacks {
		var failures = 0
		var failure: RRError? = null
		var completed = 0
		var cached = 0
		var stream: MemoryDataStream? = null
		val finished = CountDownLatch(1)
		override fun onFailure(error: RRError) {
			failure = error
			failures++
			finished.countDown()
		}
		override fun onDataStreamAvailable(
			streamFactory: GenericFactory<SeekableInputStream, IOException>, timestamp: TimestampUTC,
			session: UUID, fromCache: Boolean, mimetype: String?
		) {
			if (!fromCache) streamFactory.create().use {
				stream = ReflectionHelpers.getField(it, "mStream")
			}
		}
		override fun onDataStreamComplete(
			streamFactory: GenericFactory<SeekableInputStream, IOException>, timestamp: TimestampUTC,
			session: UUID, fromCache: Boolean, mimetype: String?
		) { completed++; finished.countDown() }
		override fun onCacheFileWritten(
			cacheFile: CacheManager.ReadableCacheFile, timestamp: TimestampUTC, session: UUID,
			fromCache: Boolean, mimetype: String?
		) { cached++ }
	}

	private fun request(capture: Capture, target: UriString = url) = CacheRequest(target,
		RedditAccountManager.getAnon(), null, Priority(Constants.Priority.INLINE_IMAGE_PREVIEW),
		DownloadStrategyIfNotCached.INSTANCE, Constants.FileType.INLINE_IMAGE_PREVIEW,
		CacheRequest.DownloadQueueType.IMMEDIATE, false, app, capture)

	@Test
	fun oversizedDeclaredLengthFailsBeforeAllocationOrReading() {
		val input = SizedBody(1)
		response(256L * 1024 * 1024, input)
		val capture = Capture()
		CacheDownload(request(capture), manager).doDownload()
		assertEquals(1, capture.failures)
		assertEquals(0, capture.completed)
		assertNull(capture.stream)
		assertEquals(0, input.readCount)
		assertTrue(input.closed)
		assertTrue(CacheDownload.chooseInitialBufferSize(256L * 1024 * 1024,
			Constants.FileType.INLINE_IMAGE_PREVIEW) <= cap)
	}

	@Test
	fun absentAndDishonestLengthsCannotGrowTheStreamPastTheLimit() {
		for (declared in listOf(-1L, 1L, cap.toLong())) {
			val input = SizedBody(cap + 1)
			response(declared, input)
			val capture = Capture()
			CacheDownload(request(capture), manager).doDownload()
			assertEquals(1, capture.failures)
			assertEquals(0, capture.completed)
			assertEquals(0, capture.cached)
			assertEquals(cap, capture.stream!!.size())
			assertTrue(capture.stream!!.capacity <= cap)
			assertTrue(input.readCount <= cap + 64 * 1024)
			assertTrue(input.closed)
			try {
				capture.stream!!.inputStream.use { it.read() }
				fail("A failed partial preview must not be consumable")
			} catch (expected: IOException) { /* Failure reaches any streaming consumer. */ }
		}
	}

	@Test
	fun aBodyAtTheLimitSucceedsWithoutOverallocatingForAnInaccurateSmallLength() {
		val input = SizedBody(cap)
		response(3L, input)
		val capture = Capture()
		CacheDownload(request(capture), manager).doDownload()
		assertEquals(0, capture.failures)
		assertEquals(1, capture.completed)
		assertEquals(cap, capture.stream!!.size())
		assertTrue(capture.stream!!.capacity <= cap)
		assertTrue(input.closed)
	}

	@Test
	fun aValidSourceOnlyGalleryBodyStillTraversesTransportAndTheBoundedDecoder() {
		val controller = Robolectric.buildActivity(ChangelogActivity::class.java).setup().visible()
		try {
			val activity = controller.get()
			val id = UrlEncodedString("only")
			val target = url
			val raw = RedditPost(id = "gallery", name = RedditIdAndType("t3_gallery"),
				url = UrlEncodedString("https://reddit.com/gallery/gallery"),
				domain = UrlEncodedString("reddit.com"), subreddit = UrlEncodedString("test"),
				num_comments = 1, score = 1,
				created_utc = RedditTimestampUTC(TimestampUTC.fromUtcSecs(0)),
				permalink = UrlEncodedString("/r/test/comments/gallery"),
				gallery_data = RedditPost.GalleryData(listOf(MaybeParseError.Ok(
					RedditPost.GalleryData.GalleryItem(id)))),
				media_metadata = mapOf(id to MaybeParseError.Ok(RedditMediaMetadata(
					"valid", "Image", "image/png", ImageMetadata(24000, 12000,
						UrlEncodedString(target.value)), "only", null))))
			val prepared = RedditPreparedPost(activity, manager, 0,
				RedditParsedPost(activity, raw, false), TimestampUTC.fromUtcSecs(0),
				false, false, false, true)
			val details = InlinePreviewLoader.calculatePreviewDetails(activity, prepared)!!
			assertEquals(target, details.url)
			val bitmap = Bitmap.createBitmap(1200, 600, Bitmap.Config.ARGB_8888)
			val bytes = ByteArrayOutputStream()
			assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, bytes))
			bitmap.recycle()
			assertTrue("Valid transport fixture must fit the byte limit", bytes.size() <= cap)
			response(-1, ByteArrayInputStream(bytes.toByteArray()))
			val capture = object : Capture() {
				override fun onDataStreamComplete(
					streamFactory: GenericFactory<SeekableInputStream, IOException>,
					timestamp: TimestampUTC, session: UUID, fromCache: Boolean, mimetype: String?
				) {
					streamFactory.create().use { stream ->
						val decoded = ScaledBitmapDecoder.decodeToFitWithin(stream,
							details.boxWidthPx, details.boxHeightPx)
						assertTrue(decoded.width <= details.boxWidthPx)
						assertTrue(decoded.height <= details.boxHeightPx)
						decoded.recycle()
					}
					super.onDataStreamComplete(streamFactory, timestamp, session, fromCache, mimetype)
				}
			}
			CacheDownload(request(capture, details.url), manager).doDownload()
			assertEquals(capture.failure.toString(), 0, capture.failures)
			assertEquals(1, capture.completed)
			assertTrue(capture.stream!!.capacity <= cap)
			// The same positive-int metadata contract also covers extreme tall sources.
			val extreme = raw.copy(media_metadata = mapOf(id to MaybeParseError.Ok(
				RedditMediaMetadata("valid", "Image", "image/png", ImageMetadata(50, 150000000,
					UrlEncodedString(target.value)), "only", null))))
			val tall = RedditPreparedPost(activity, manager, 0,
				RedditParsedPost(activity, extreme, false), TimestampUTC.fromUtcSecs(0),
				false, false, false, true)
			assertTrue(InlinePreviewLoader.calculatePreviewDetails(activity, tall)!!.boxHeightPx > 1)
		} finally { controller.pause().stop().destroy() }
	}

	@Test
	fun oversizedOrCompressedCacheEntriesFailBeforeAnyImageStreamIsOpened() {
		for (compression in listOf(CacheCompressionType.NONE, CacheCompressionType.ZSTD)) {
			val target = url
			val writable = manager.openNewCacheFile(target, RedditAccountManager.getAnon(),
				Constants.FileType.IMAGE, UUID.randomUUID(), "image/png", compression)
			val bytes = byteArrayOf(1, 2, 3)
			// The compressed entry must be rejected without invoking the native reader.
			// Labeling a tiny raw fixture avoids needing Android's Zstd JNI to create it.
			writable.writeExternally().writeBytes(bytes)
			writable.onWriteFinished()
			if (compression == CacheCompressionType.NONE) {
				RandomAccessFile(writable.readableCacheFile.file.get(), "rw").use {
					it.setLength(cap.toLong() + 1)
				}
			}
			val capture = Capture()
			manager.makeRequest(request(capture, target))
			assertTrue(capture.finished.await(5, TimeUnit.SECONDS))
			assertEquals(1, capture.failures)
			assertEquals(0, capture.completed)
			assertEquals(0, capture.cached)
			assertNull(capture.stream)
		}
	}
}

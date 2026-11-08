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
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.quantumbadger.redreader.account.RedditAccount
import org.quantumbadger.redreader.cache.CacheDownload
import org.quantumbadger.redreader.cache.CacheRequest
import org.quantumbadger.redreader.cache.CacheRequestCallbacks
import org.quantumbadger.redreader.cache.downloadstrategy.DownloadStrategyAlways
import org.quantumbadger.redreader.cache.downloadstrategy.DownloadStrategyIfNotCached
import org.quantumbadger.redreader.common.Constants
import org.quantumbadger.redreader.common.General
import org.quantumbadger.redreader.common.Priority
import org.quantumbadger.redreader.common.RRError
import org.quantumbadger.redreader.common.SharedPrefsWrapper
import org.quantumbadger.redreader.common.UriString
import org.quantumbadger.redreader.http.FailedRequestBody
import org.quantumbadger.redreader.http.HTTPBackend
import org.quantumbadger.redreader.http.PostField
import org.quantumbadger.redreader.http.body.HTTPRequestBody
import org.quantumbadger.redreader.http.okhttp.OKHTTPBackend
import org.quantumbadger.redreader.reddit.RedditMutationCallbacks
import org.quantumbadger.redreader.reddit.api.MultiredditApi
import org.quantumbadger.redreader.reddit.api.MultiredditTarget
import org.quantumbadger.redreader.reddit.things.SubredditCanonicalId
import org.quantumbadger.redreader.reddit.url.MultiredditPostListURL
import org.quantumbadger.redreader.reddit.url.RedditURLParser
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.io.InputStream
import java.net.URLDecoder
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MultiredditProtocolTest {

	private val app get() = RuntimeEnvironment.getApplication()
	private val alice = RedditAccount("Alice", null, 0, null)
	private val bob = RedditAccount("Bob", null, 0, null)
	private val anonymous = RedditAccount("", null, 0, null)
	private val listener = object : MultiredditApi.ActionListener {
		override fun onSuccess() { }
		override fun onFailure(error: RRError) { }
	}
	private val callbacks = object : CacheRequestCallbacks {
		override fun onFailure(error: RRError) { }
	}

	@Before
	fun initPrefs() {
		ReflectionHelpers.getStaticField<AtomicReference<SharedPrefsWrapper>>(
			General::class.java, "mPrefs"
		).set(null)
		val prefs = General.getSharedPrefs(app)
		ReflectionHelpers.getField<SharedPreferences>(prefs, "mPrefs").edit().clear().commit()
		ReflectionHelpers.setStaticField(
			org.quantumbadger.redreader.common.PrefsUtility::class.java, "sharedPrefs", prefs)
		ReflectionHelpers.setStaticField(
			org.quantumbadger.redreader.common.PrefsUtility::class.java, "mRes", app.resources)
		ReflectionHelpers.setStaticField(
			org.quantumbadger.redreader.common.PrefsUtility::class.java, "appContext", app)
	}

	private fun form(request: CacheRequest): Map<String, String> {
		val body = request.requestBody.asNullable() as HTTPRequestBody.PostFields
		return body.encodeFields().split('&').associate { field ->
			val parts = field.split('=', limit = 2)
			URLDecoder.decode(parts[0], "UTF-8") to URLDecoder.decode(parts[1], "UTF-8")
		}
	}

	@Test
	fun createUsesPostPrivateModelAndCapturedOwner() {
		val target = MultiredditTarget.forName(alice, "news")
		val request = MultiredditApi.newCreateRequest(app, alice, target,
			listOf(SubredditCanonicalId("Android"), SubredditCanonicalId("android")), listener)
		assertEquals(HTTPBackend.Method.POST, request.method)
		assertEquals("/api/multi/user/alice/m/news", request.url.toUri().path)
		val model = org.json.JSONObject(form(request).getValue("model"))
		assertEquals("private", model.getString("visibility"))
		assertEquals("news", model.getString("display_name"))
		assertEquals(1, model.getJSONArray("subreddits").length())
		assertSame(alice, request.user)
		assertFalse(request.cache)
		assertFalse(request.precache)
		assertSame(DownloadStrategyAlways.INSTANCE, request.downloadStrategy)
		assertEquals(CacheRequest.DownloadQueueType.REDDIT_API, request.queueType)
	}

	@Test
	fun addAndRemoveUseFullOwnedIdentityAndDistinctMethods() {
		val target = MultiredditTarget.forName(alice, "news")
		val add = MultiredditApi.newAddSubredditRequest(app, alice, target,
			SubredditCanonicalId("/r/Android"), listener)
		val remove = MultiredditApi.newRemoveSubredditRequest(app, alice, target,
			SubredditCanonicalId("android"), listener)
		assertEquals("/api/multi/user/alice/m/news/r/android", add.url.toUri().path)
		assertEquals(add.url, remove.url)
		assertEquals(HTTPBackend.Method.PUT, add.method)
		assertEquals(HTTPBackend.Method.DELETE, remove.method)
		assertEquals("android", org.json.JSONObject(form(add).getValue("model")).getString("name"))
		assertFalse(remove.requestBody.isPresent)
		assertFalse(remove.cache)
	}

	@Test
	fun otherOwnersCannotBeMutatedEvenWithTheSameMultiName() {
		val url = RedditURLParser.parse(android.net.Uri.parse("https://reddit.com/user/Bob/m/news"))
			.asMultiredditPostListURL()
		val target = MultiredditTarget.fromListing(alice, url)
		assertEquals("bob", target.owner)
		assertFalse(target.isOwnedBy(alice))
		assertTrue(target.isOwnedBy(bob))
		assertThrows(IllegalArgumentException::class.java) {
			MultiredditApi.newDeleteRequest(app, alice, target, listener)
		}
		assertThrows(IllegalArgumentException::class.java) {
			MultiredditApi.newRemoveSubredditRequest(app, alice, target,
				SubredditCanonicalId("android"), listener)
		}
	}

	@Test
	fun meListingResolvesAgainstOnlyCapturedAccount() {
		val url = MultiredditPostListURL.getMultireddit("news").asMultiredditPostListURL()
		val target = MultiredditTarget.fromListing(alice, url)
		assertEquals("/user/alice/m/news", target.getDisplayPath())
		assertThrows(IllegalArgumentException::class.java) {
			MultiredditApi.newDeleteRequest(app, bob, target, listener)
		}
	}

	@Test
	fun invalidNamesAndNonSubredditDestinationsAreRejectedBeforeQueueing() {
		for(name in listOf("", "a/b", "a+b", "a?x", "a b", "a".repeat(51))) {
			assertFalse(MultiredditTarget.isValidName(name))
			assertThrows(IllegalArgumentException::class.java) {
				MultiredditTarget.forName(alice, name)
			}
		}
		assertThrows(IllegalArgumentException::class.java) {
			MultiredditTarget.forName(anonymous, "news")
		}
		for(name in listOf("android+java", "/user/example", "all", "popular")) {
			assertFalse(MultiredditApi.canAddSubreddit(SubredditCanonicalId(name)))
		}
	}

	@Test
	fun existingGetPostAndPrecacheConstructorsPreserveSemantics() {
		val url = UriString("https://example.org/fixture")
		val get = CacheRequest(url, anonymous, null, Priority(0),
			DownloadStrategyIfNotCached.INSTANCE, 0, CacheRequest.DownloadQueueType.IMMEDIATE,
			app, callbacks)
		val post = CacheRequest(url, anonymous, null, Priority(0),
			DownloadStrategyAlways.INSTANCE, 0, CacheRequest.DownloadQueueType.IMMEDIATE,
			HTTPRequestBody.PostFields(PostField("x", "y")), app, callbacks)
		val precache = CacheRequest.newPrecacheRequest(url, anonymous, Priority(0),
			DownloadStrategyIfNotCached.INSTANCE, 0, CacheRequest.DownloadQueueType.IMAGE_PRECACHE,
			app, callbacks)
		assertEquals(HTTPBackend.Method.GET, get.method)
		assertTrue(get.cache)
		assertEquals(HTTPBackend.Method.POST, post.method)
		assertFalse(post.cache)
		assertEquals(HTTPBackend.Method.GET, precache.method)
		assertTrue(precache.cache)
		assertTrue(precache.precache)
		assertThrows(IllegalArgumentException::class.java) {
			HTTPBackend.RequestDetails(url, HTTPRequestBody.PostFields(), HTTPBackend.Method.GET)
		}
	}

	@Test
	fun memberReadsAreFreshAndValidateReturnedShape() {
		val request = MultiredditApi.newMembersRequest(app, alice,
			MultiredditTarget.forName(alice, "news"), object : MultiredditApi.MembersListener {
				override fun onSuccess(members: List<String>) { }
				override fun onFailure(error: RRError) { }
			})
		assertFalse(request.cache)
		assertEquals(HTTPBackend.Method.GET, request.method)
		assertSame(DownloadStrategyAlways.INSTANCE, request.downloadStrategy)
		assertEquals(listOf("android", "java"), MultiredditApi.parseMembers(
			"""{"data":{"subreddits":[{"name":"Java"},{"name":"android"}]}}"""))
		for(response in listOf("", "[]", "{}", """{"data":{}}""",
			"""{"data":{"subreddits":[{}]}}""")) {
			assertThrows(IllegalArgumentException::class.java) { MultiredditApi.parseMembers(response) }
		}
	}

	@Test
	fun ownedNameSuggestionsDoNotIncludeAnotherOwnersList() {
		val response = """[
			{"data":{"name":"news","owner":"alice","path":"/user/alice/m/news"}},
			{"data":{"name":"secret","owner":"bob","path":"/user/bob/m/secret"}}
		]"""
		assertEquals(listOf("news"), MultiredditApi.parseOwnedNames(response, alice))
		assertThrows(IllegalArgumentException::class.java) {
			MultiredditApi.parseOwnedNames("""[{"data":{"name":"news"}}]""", alice)
		}
	}

	@Test
	fun mutationBodyErrorsAreDetectedIncludingNestedHttp200Errors() {
		assertNull(RedditMutationCallbacks.errorInResponse(""))
		assertNull(RedditMutationCallbacks.errorInResponse("{}"))
		assertNull(RedditMutationCallbacks.errorInResponse("""{"json":{"errors":[]}}"""))
		assertNull(RedditMutationCallbacks.errorInResponse("""{"error":false}"""))
		assertNotNull(RedditMutationCallbacks.errorInResponse(
			"""{"json":{"errors":[["BAD_MULTI_NAME","Invalid name",""]]}}"""))
		assertNotNull(RedditMutationCallbacks.errorInResponse("""{"error":403}"""))
		assertNotNull(RedditMutationCallbacks.errorInResponse("""{"success":false}"""))
		assertThrows(IllegalArgumentException::class.java) {
			RedditMutationCallbacks.errorInResponse("<html>login</html>")
		}
	}

	private data class Exchange(
		val method: String,
		val path: String,
		val authorization: String?,
		val body: String,
		val successBody: String?,
		val failureStatus: Int?
	)

	private fun exchange(method: HTTPBackend.Method, status: Int): Exchange {
		val backend = HTTPBackend.backend as OKHTTPBackend
		val oldClient = ReflectionHelpers.getField<OkHttpClient>(backend, "mClient")
		var sent: okhttp3.Request? = null
		val client = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
			sent = chain.request()
			Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
				.code(status).message("Fixture")
				.body((if(status == 204) "" else "{}").toResponseBody(
					"application/json".toMediaType())).build()
		}).build()
		ReflectionHelpers.setField(backend, "mClient", client)
		try {
			val body = if(method == HTTPBackend.Method.POST || method == HTTPBackend.Method.PUT)
				HTTPRequestBody.PostFields(PostField("model", """{"name":"android"}""")) else null
			val request = CacheRequest.newUncachedRequest(
				UriString("https://example.org/api/multi/user/alice/m/news/r/android"),
				anonymous, method, body, app, callbacks)
			val download = CacheDownload(request, null)
			val wire = ReflectionHelpers.getField<HTTPBackend.Request>(download, "mRequest")
			wire.addHeader("Authorization", "bearer fixture-only")
			var success: String? = null
			var failure: Int? = null
			wire.executeInThisThread(object : HTTPBackend.Listener {
				override fun onSuccess(mimetype: String?, bodyBytes: Long?, body: InputStream?) {
					success = body?.bufferedReader()?.readText()
				}
				override fun onError(type: CacheRequest.RequestFailureType, exception: Throwable?,
					httpStatus: Int?, body: FailedRequestBody?) {
					failure = httpStatus
				}
			})
			val outgoing = sent!!
			val encoded = Buffer().also { outgoing.body?.writeTo(it) }.readUtf8()
			return Exchange(outgoing.method, outgoing.url.encodedPath,
				outgoing.header("Authorization"), encoded, success, failure)
		} finally {
			ReflectionHelpers.setField(backend, "mClient", oldClient)
			client.dispatcher.executorService.shutdown()
			client.connectionPool.evictAll()
		}
	}

	@Test
	fun realBackendEmitsRequestedVerbsAndBodiesWithoutLosingHeaders() {
		for(method in HTTPBackend.Method.values()) {
			val result = exchange(method, 200)
			assertEquals(method.name, result.method)
			assertEquals("/api/multi/user/alice/m/news/r/android", result.path)
			assertEquals("bearer fixture-only", result.authorization)
			assertEquals("{}", result.successBody)
			assertNull(result.failureStatus)
			if(method == HTTPBackend.Method.POST || method == HTTPBackend.Method.PUT) {
				assertTrue(result.body.startsWith("model="))
			} else {
				assertEquals("", result.body)
			}
		}
	}

	@Test
	fun createdAndEmptyDeleteResponsesAreSuccessButConflictIsFailure() {
		assertEquals("{}", exchange(HTTPBackend.Method.POST, 201).successBody)
		assertEquals("", exchange(HTTPBackend.Method.DELETE, 204).successBody)
		assertEquals("{}", exchange(HTTPBackend.Method.POST, 202).successBody)
		val conflict = exchange(HTTPBackend.Method.POST, 409)
		assertEquals(409, conflict.failureStatus)
		assertNull(conflict.successBody)
	}
}

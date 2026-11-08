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
import android.content.Intent
import android.os.Bundle
import android.os.Looper
import android.view.View
import androidx.appcompat.view.menu.MenuBuilder
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.lifecycle.Lifecycle
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.quantumbadger.redreader.R
import org.quantumbadger.redreader.account.RedditAccount
import org.quantumbadger.redreader.account.RedditAccountManager
import org.quantumbadger.redreader.activities.InboxListingActivity
import org.quantumbadger.redreader.cache.CacheManager
import org.quantumbadger.redreader.cache.CacheRequest
import org.quantumbadger.redreader.common.General
import org.quantumbadger.redreader.common.PrefsUtility
import org.quantumbadger.redreader.common.RRError
import org.quantumbadger.redreader.common.SharedPrefsWrapper
import org.quantumbadger.redreader.common.datastream.MemoryDataStream
import org.quantumbadger.redreader.common.time.TimestampUTC
import org.quantumbadger.redreader.http.body.HTTPRequestBody
import org.quantumbadger.redreader.reddit.InboxReadOnExit
import org.quantumbadger.redreader.reddit.api.RedditOAuth
import org.quantumbadger.redreader.reddit.kthings.RedditIdAndType
import org.quantumbadger.redreader.reddit.prepared.RedditChangeDataManager
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.LooperMode
import org.robolectric.shadow.api.Shadow
import org.robolectric.util.ReflectionHelpers
import java.net.URLDecoder
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], shadows = [InboxExitActivityTest.RecordingCache::class])
@LooperMode(LooperMode.Mode.PAUSED)
class InboxExitActivityTest {

	@Implements(value = CacheManager::class, isInAndroidSdk = false)
	class RecordingCache {
		@Implementation
		fun makeRequest(request: CacheRequest) {
			requests.add(request)
		}

		companion object {
			val requests = mutableListOf<CacheRequest>()

			@JvmStatic
			@Implementation
			fun getInstance(context: Context): CacheManager =
				Shadow.newInstanceOf(CacheManager::class.java)
		}
	}

	private val app get() = RuntimeEnvironment.getApplication()
	private val alice = RedditAccount("Alice", RedditOAuth.RefreshToken("alice-token"), 1, null)
	private val bob = RedditAccount("Bob", RedditOAuth.RefreshToken("bob-token"), 2, null)
	private lateinit var accounts: RedditAccountManager
	private lateinit var prefs: SharedPrefsWrapper
	private val hosts = mutableListOf<ActivityController<InboxListingActivity>>()

	@Before
	fun setUp() {
		RecordingCache.requests.clear()
		ReflectionHelpers.setStaticField(General::class.java, "lastBackPress", -1000L)
		ReflectionHelpers.setStaticField(RedditAccountManager::class.java, "singleton", null)
		ReflectionHelpers.getStaticField<MutableMap<*, *>>(
			RedditChangeDataManager::class.java, "INSTANCE_MAP").clear()
		ReflectionHelpers.getStaticField<AtomicReference<SharedPrefsWrapper>>(
			General::class.java, "mPrefs").set(null)
		prefs = General.getSharedPrefs(app)
		val raw = ReflectionHelpers.getField<android.content.SharedPreferences>(prefs, "mPrefs")
		raw.edit().clear().commit()
		ReflectionHelpers.setStaticField(PrefsUtility::class.java, "sharedPrefs", prefs)
		ReflectionHelpers.setStaticField(PrefsUtility::class.java, "mRes", app.resources)
		ReflectionHelpers.setStaticField(PrefsUtility::class.java, "appContext", app)
		accounts = RedditAccountManager.getInstance(app)
		ReflectionHelpers.setField(accounts, "accountsCache", arrayListOf(alice, bob))
		ReflectionHelpers.setField(accounts, "defaultAccountCache", alice)
	}

	@After
	fun tearDown() {
		hosts.forEach {
			if(!ReflectionHelpers.getField<Boolean>(it.get(), "mDestroyed")) {
				if(it.get().lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) it.pause()
				if(it.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) it.stop()
				it.destroy()
			}
		}
		idle()
	}

	private fun idle() = Shadows.shadowOf(Looper.getMainLooper()).idle()

	private fun open(
		mode: String = "inbox",
		state: Bundle? = null
	): ActivityController<InboxListingActivity> {
		val intent = Intent(app, InboxListingActivity::class.java).putExtra("inboxType", mode)
		val host = Robolectric.buildActivity(InboxListingActivity::class.java, intent)
			.create(state).start().resume().visible()
		hosts.add(host)
		idle()
		return host
	}

	private fun listing(count: Int = 30, readIndex: Int = -1): String {
		val items = (0 until count).joinToString(",") { i ->
			"""{"kind":"t4","data":{"id":"m$i","name":"t4_m$i","author":"sender",
			"dest":"Alice","subject":"Message $i","body":"A message $i",
			"body_html":"&lt;p&gt;A message $i&lt;/p&gt;","created_utc":1700000000.0,
			"new":${i != readIndex}}}"""
		}
		return """{"kind":"Listing","data":{"children":[$items],"after":null}}"""
	}

	private fun complete(request: CacheRequest, body: String) {
		request.notifyDataStreamComplete(
			{ MemoryDataStream(body.toByteArray()).inputStream },
			TimestampUTC.now(), UUID.randomUUID(), false, "application/json")
		idle()
	}

	private fun render(activity: InboxListingActivity) {
		val decor = activity.window.decorView
		decor.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
			View.MeasureSpec.makeMeasureSpec(1800, View.MeasureSpec.EXACTLY))
		decor.layout(0, 0, 1080, 1800)
		decor.viewTreeObserver.dispatchOnPreDraw()
		idle()
	}

	private fun populate(host: ActivityController<InboxListingActivity>, count: Int = 30) {
		val fetch = RecordingCache.requests.last { it.url.value.contains("/message/") }
		complete(fetch, listing(count))
		render(host.get())
		assertEquals(count, ReflectionHelpers.getField<ArrayList<*>>(host.get(), "mItems").size)
	}

	private fun visit(activity: InboxListingActivity): InboxReadOnExit =
		ReflectionHelpers.getField(activity, "mReadOnExit")

	private fun writes() = RecordingCache.requests.filter {
		it.url.toUri().path == "/api/read_message"
	}

	private fun ids(request: CacheRequest): List<String> {
		val body = (request.requestBody.get() as HTTPRequestBody.PostFields).encodeFields()
		val encoded = body.split('&').single { it.startsWith("id=") }.substring(3)
		return URLDecoder.decode(encoded, "UTF-8").split(',')
	}

	private fun enable(value: Boolean = true) {
		prefs.edit().putBoolean("inbox_mark_as_read_when_back", value).apply()
	}

	@Test
	fun fetchDoesNotMarkFetchedUnseenItemsRead() {
		open()
		val url = RecordingCache.requests.single().url.value
		assertTrue(url.contains("mark=false"))
		assertFalse(url.contains("mark=true"))
		assertTrue(writes().isEmpty())
	}

	@Test
	fun disabledSettingDoesNotWriteOnFinish() {
		val host = open()
		populate(host)
		assertTrue(visit(host.get()).savedIds().isNotEmpty())
		host.get().finish()
		assertTrue(writes().isEmpty())
	}

	@Test
	fun optInMarksOnlyVisibleUnreadIdsAfterSuccess() {
		enable()
		val host = open()
		populate(host)
		val seen = visit(host.get()).savedIds()
		assertTrue(seen.isNotEmpty())
		assertTrue(seen.size < 30)
		val changes = RedditChangeDataManager.getInstance(alice)
		host.get().finish()
		assertEquals(seen.toSet(), writes().flatMap { ids(it) }.toSet())
		assertTrue(seen.none { changes.isRead(RedditIdAndType(it)) })
		writes().forEach { complete(it, "") }
		assertTrue(seen.all { changes.isRead(RedditIdAndType(it)) })
		assertFalse(changes.isRead(RedditIdAndType("t4_m29")))
	}

	@Test
	fun unseenNewlyDownloadedRowsAreNotIncludedOnExit() {
		enable()
		val host = open()
		populate(host)
		val seen = visit(host.get()).savedIds().toSet()
		val fetch = RecordingCache.requests.first()
		complete(fetch, listing(1).replace("m0", "newmessage"))
		host.get().finish()
		assertEquals(seen, writes().flatMap { ids(it) }.toSet())
		assertFalse(writes().flatMap { ids(it) }.contains("t4_newmessage"))
	}

	@Test
	fun scrollingAddsVisibleRowsButNeverPreloadedOffscreenRows() {
		enable()
		val host = open()
		populate(host)
		val first = visit(host.get()).savedIds().toSet()
		val recycler = ReflectionHelpers.getField<RecyclerView>(host.get(), "mRecyclerView")
		(recycler.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(15, 0)
		recycler.requestLayout()
		render(host.get())
		render(host.get())
		val scrolled = visit(host.get()).savedIds().toSet()
		assertTrue(scrolled.size > first.size)
		assertTrue(scrolled.contains("t4_m15"))
		assertFalse(scrolled.contains("t4_m29"))
		host.get().finish()
		assertEquals(scrolled, writes().flatMap { ids(it) }.toSet())
	}

	@Test
	fun failureLeavesItemsUnreadAndUsesNoDeadActivityDialog() {
		enable()
		val host = open()
		populate(host)
		val seen = visit(host.get()).savedIds()
		host.get().finish()
		host.pause().stop().destroy()
		writes().forEach { it.notifyFailure(RRError("Offline", "No connection", false)) }
		idle()
		val changes = RedditChangeDataManager.getInstance(alice)
		assertTrue(seen.none { changes.isRead(RedditIdAndType(it)) })
	}

	@Test
	fun accountSwitchDoesNotRetargetSeenIdsToNewDefault() {
		enable()
		val host = open()
		populate(host)
		ReflectionHelpers.setField(accounts, "defaultAccountCache", bob)
		host.get().finish()
		assertTrue(writes().isNotEmpty())
		assertTrue(writes().all { it.user === alice })
		writes().forEach { complete(it, "") }
		assertFalse(RedditChangeDataManager.getInstance(bob).isRead(RedditIdAndType("t4_m0")))
	}

	@Test
	fun removedAccountNeverFallsBackToAnotherAccount() {
		enable()
		val host = open()
		populate(host)
		ReflectionHelpers.setField(accounts, "accountsCache", arrayListOf(bob))
		ReflectionHelpers.setField(accounts, "defaultAccountCache", bob)
		host.get().finish()
		assertTrue(writes().isEmpty())
	}

	@Test
	fun backgroundAndConfigurationStopDoNotSubmitReadRequests() {
		enable()
		val host = open()
		populate(host)
		host.pause().stop()
		assertFalse(host.get().isFinishing)
		assertTrue(writes().isEmpty())
		host.restart().start().resume().visible()
		render(host.get())
		host.get().finish()
		assertTrue(writes().isNotEmpty())
	}

	@Test
	fun recreationRestoresSeenIdsAndCapturedAccount() {
		enable()
		val host = open()
		populate(host)
		val seen = visit(host.get()).savedIds().toSet()
		val state = Bundle()
		host.pause().saveInstanceState(state).stop().destroy()
		assertTrue(writes().isEmpty())
		ReflectionHelpers.setField(accounts, "defaultAccountCache", bob)
		val restored = open(state = state)
		assertSame(alice, ReflectionHelpers.getField(restored.get(), "mUser"))
		assertEquals(seen, visit(restored.get()).savedIds().toSet())
		restored.get().finish()
		assertEquals(seen, writes().flatMap { ids(it) }.toSet())
		assertTrue(writes().all { it.user === alice })
	}

	@Test
	fun sentDoesNotOfferOrSendAutomaticReadWrites() {
		enable()
		val host = open("sent")
		populate(host)
		val menu = MenuBuilder(host.get())
		host.get().onCreateOptionsMenu(menu)
		assertNull(menu.findItem(3))
		host.get().finish()
		assertTrue(writes().isEmpty())
	}

	@Test
	fun legacyModmailMarksOnlyItsOwnVisibleMessageIds() {
		enable()
		val host = open("modmail")
		populate(host)
		val seen = visit(host.get()).savedIds().toSet()
		assertTrue(seen.isNotEmpty())
		assertTrue(seen.size < 30)
		val menu = MenuBuilder(host.get())
		host.get().onCreateOptionsMenu(menu)
		assertTrue(menu.findItem(3).isChecked)
		host.get().finish()
		assertEquals(seen, writes().flatMap { ids(it) }.toSet())
		assertTrue(writes().all { it.user === alice })
		assertFalse(RecordingCache.requests.any {
			it.url.toUri().path == "/api/read_all_messages"
		})
	}

	@Test
	fun menuToggleIsPersistentAndTakesEffectOnlyWhenEnabled() {
		val host = open()
		populate(host)
		val menu = MenuBuilder(host.get())
		host.get().onCreateOptionsMenu(menu)
		val toggle = menu.findItem(3)
		assertFalse(toggle.isChecked)
		host.get().onOptionsItemSelected(toggle)
		assertTrue(toggle.isChecked)
		assertTrue(prefs.getBoolean("inbox_mark_as_read_when_back", false))
		host.get().onOptionsItemSelected(toggle)
		assertFalse(toggle.isChecked)
		host.get().finish()
		assertTrue(writes().isEmpty())
	}

	@Test
	fun duplicateFinishOrStopCannotSubmitTwice() {
		enable()
		val host = open()
		populate(host)
		host.get().finish()
		val count = writes().size
		host.get().finish()
		host.pause().stop()
		assertEquals(count, writes().size)
	}

	@Test
	fun destroyCancelsFetchAndDropsLateRows() {
		val host = open()
		val fetch = RecordingCache.requests.single()
		host.pause().stop().destroy()
		assertTrue(ReflectionHelpers.getField(fetch, "cancelled"))
		complete(fetch, listing())
		assertTrue(ReflectionHelpers.getField<ArrayList<*>>(host.get(), "mItems").isEmpty())
		assertTrue(writes().isEmpty())
	}

	@Test
	fun explicitMarkAllStillUsesTheAllMessagesEndpoint() {
		val host = open()
		populate(host)
		val menu = MenuBuilder(host.get())
		host.get().onCreateOptionsMenu(menu)
		host.get().onOptionsItemSelected(menu.findItem(0))
		assertTrue(RecordingCache.requests.any {
			it.url.toUri().path == "/api/read_all_messages"
		})
		assertTrue(writes().isEmpty())
	}

	@Test
	fun knownReadAndUnknownReadStateAreNotAutoMarked() {
		enable()
		val host = open()
		complete(RecordingCache.requests.single(),
			listing(2, readIndex = 0).replace("\"new\":true", "\"new\":null"))
		render(host.get())
		assertTrue(visit(host.get()).savedIds().isEmpty())
		host.get().finish()
		assertTrue(writes().isEmpty())
	}

	@Test
	fun commentRepliesUseTheSameVisibleItemReadPath() {
		enable()
		val host = open()
		complete(RecordingCache.requests.single(), """
			{"kind":"Listing","data":{"children":[{"kind":"t1","data":{
			"id":"m0","name":"t1_m0","author":"sender","subreddit":"android",
			"body":"A reply","body_html":"&lt;p&gt;A reply&lt;/p&gt;",
			"link_id":"t3_post","parent_id":"t3_post",
			"context":"/r/android/comments/post/title/m0",
			"created_utc":1700000000.0,"new":true}}],"after":null}}
		""".trimIndent())
		render(host.get())
		host.get().finish()
		assertEquals(listOf("t1_m0"), writes().flatMap { ids(it) })
	}

	@Test
	fun successfulAndFailedBatchesUpdateOnlyTheirOwnIds() {
		enable()
		val state = Bundle().apply {
			putString("inbox_account", "Alice")
			putStringArrayList("inbox_viewed_unread_ids",
				ArrayList((0 until 57).map { "t4_old$it" }))
		}
		val host = open(state = state)
		host.get().finish()
		val requests = writes()
		assertEquals(listOf(25, 25, 7), requests.map { ids(it).size })
		complete(requests[0], "")
		requests[1].notifyFailure(RRError("Offline", "No connection", false))
		complete(requests[2], "")
		val changes = RedditChangeDataManager.getInstance(alice)
		assertTrue(ids(requests[0]).all { changes.isRead(RedditIdAndType(it)) })
		assertTrue(ids(requests[1]).none { changes.isRead(RedditIdAndType(it)) })
		assertTrue(ids(requests[2]).all { changes.isRead(RedditIdAndType(it)) })
	}

	@Test
	fun backDispatcherUsesTheReadOnExitPath() {
		enable()
		val host = open()
		populate(host)
		host.get().onBackPressedDispatcher.onBackPressed()
		idle()
		assertTrue(host.get().isFinishing)
		// The platform finishes through its transition path. Complete the lifecycle
		// boundary which submits the captured IDs when finish() itself is bypassed.
		host.pause().stop()
		assertTrue(writes().isNotEmpty())
	}

	@Test
	fun toolbarUpUsesTheReadOnExitPath() {
		enable()
		val host = open()
		populate(host)
		host.get().findViewById<View>(R.id.actionbar_title_outer).performClick()
		idle()
		assertTrue(host.get().isFinishing)
		host.pause().stop()
		assertTrue(writes().isNotEmpty())
	}

	@Test
	fun manualReadButtonKeepsTheRowsCapturedAccount() {
		val host = open()
		populate(host)
		ReflectionHelpers.setField(accounts, "defaultAccountCache", bob)
		val recycler = ReflectionHelpers.getField<RecyclerView>(host.get(), "mRecyclerView")
		recycler.getChildAt(0).findViewById<View>(R.id.inbox_item_button_mark_read).performClick()
		assertEquals(1, writes().size)
		assertSame(alice, writes().single().user)
	}

}

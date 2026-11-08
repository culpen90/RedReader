package org.quantumbadger.redreader.fragments

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.AutoCompleteTextView
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.Lifecycle
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.quantumbadger.redreader.account.RedditAccount
import org.quantumbadger.redreader.R
import org.quantumbadger.redreader.account.RedditAccountManager
import org.quantumbadger.redreader.activities.ChangelogActivity
import org.quantumbadger.redreader.activities.PostListingActivity
import org.quantumbadger.redreader.adapters.MainMenuListingManager
import org.quantumbadger.redreader.adapters.MainMenuSelectionListener
import org.quantumbadger.redreader.cache.CacheManager
import org.quantumbadger.redreader.cache.CacheRequest
import org.quantumbadger.redreader.cache.downloadstrategy.DownloadStrategyAlways
import org.quantumbadger.redreader.common.Constants
import org.quantumbadger.redreader.common.General
import org.quantumbadger.redreader.common.PrefsUtility
import org.quantumbadger.redreader.common.SharedPrefsWrapper
import org.quantumbadger.redreader.common.datastream.MemoryDataStream
import org.quantumbadger.redreader.common.time.TimestampUTC
import org.quantumbadger.redreader.reddit.api.MultiredditActionMenus
import org.quantumbadger.redreader.reddit.api.MultiredditTarget
import org.quantumbadger.redreader.reddit.api.RedditOAuth
import org.quantumbadger.redreader.http.HTTPBackend
import org.quantumbadger.redreader.reddit.kthings.RedditIdAndType
import org.quantumbadger.redreader.reddit.url.PostListingURL
import org.quantumbadger.redreader.reddit.url.SubredditPostListURL
import org.quantumbadger.redreader.views.list.GroupedRecyclerViewItemListItemView
import org.quantumbadger.redreader.views.PostListingHeader
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.LooperMode
import org.robolectric.android.controller.ActivityController
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowDialog
import org.robolectric.util.ReflectionHelpers
import java.util.concurrent.atomic.AtomicReference
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], shadows = [MultiredditIdentityLifecycleTest.RecordingCache::class])
@LooperMode(LooperMode.Mode.PAUSED)
class MultiredditIdentityLifecycleTest {

	@Implements(value = CacheManager::class, isInAndroidSdk = false)
	class RecordingCache {
		@Implementation
		fun makeRequest(request: CacheRequest) { requests.add(request) }

		companion object {
			val requests = mutableListOf<CacheRequest>()
			@JvmStatic
			@Implementation
			fun getInstance(context: Context): CacheManager =
				Shadow.newInstanceOf(CacheManager::class.java)
		}
	}

	private val app get() = RuntimeEnvironment.getApplication()
	private val alice = RedditAccount("Alice", RedditOAuth.RefreshToken("alice"), 1, "client")
	private val bob = RedditAccount("Bob", RedditOAuth.RefreshToken("bob"), 2, "client")
	private lateinit var accounts: RedditAccountManager
	private val controller by lazy { Robolectric.buildActivity(ChangelogActivity::class.java).setup() }
	private val activity get() = controller.get()
	private val fragments = mutableListOf<PostListingFragment>()
	private val listingHosts = mutableListOf<ActivityController<PostListingActivity>>()
	private val combination get() = SubredditPostListURL.getSubreddit("android+linux")
		.asSubredditPostListURL()
	private val target get() = MultiredditTarget.forName(alice, "news")
	private val listingUri = Uri.parse("https://www.reddit.com/me/m/news")

	@Before
	fun setUp() {
		RecordingCache.requests.clear()
		ReflectionHelpers.getStaticField<AtomicReference<SharedPrefsWrapper>>(
			General::class.java, "mPrefs").set(null)
		val prefs = General.getSharedPrefs(app)
		ReflectionHelpers.getField<SharedPreferences>(prefs, "mPrefs").edit().clear().commit()
		ReflectionHelpers.setStaticField(PrefsUtility::class.java, "sharedPrefs", prefs)
		ReflectionHelpers.setStaticField(PrefsUtility::class.java, "mRes", app.resources)
		ReflectionHelpers.setStaticField(PrefsUtility::class.java, "appContext", app)
		ReflectionHelpers.setStaticField(RedditAccountManager::class.java, "singleton", null)
		accounts = RedditAccountManager.getInstance(app)
		ReflectionHelpers.setField(accounts, "accountsCache", arrayListOf(alice, bob))
		ReflectionHelpers.setField(accounts, "defaultAccountCache", alice)
	}

	@After
	fun close() {
		fragments.forEach { it.cancel() }
		listingHosts.forEach {
			ReflectionHelpers.getField<PostListingFragment>(it.get(), "fragment").cancel()
			if(it.get().lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) it.pause()
			if(it.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) it.stop()
			it.destroy()
		}
		ShadowDialog.getLatestDialog()?.dismiss()
		idle()
		if(activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) controller.pause()
		if(activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) controller.stop()
		if(activity.lifecycle.currentState != Lifecycle.State.DESTROYED) controller.destroy()
		idle()
	}

	private fun idle() { Shadows.shadowOf(Looper.getMainLooper()).idle() }
	private fun switchDefault() { ReflectionHelpers.setField(accounts, "defaultAccountCache", bob) }
	private fun removeAlice() {
		ReflectionHelpers.setField(accounts, "accountsCache", arrayListOf(bob))
		switchDefault()
	}

	private fun openListing(state: Bundle? = null): PostListingFragment =
		PostListingFragment(activity, state, listingUri, null, true).also {
			fragments.add(it)
			idle()
		}

	private fun nextPage(fragment: PostListingFragment) {
		ReflectionHelpers.setField(fragment, "mAfter", RedditIdAndType("t3_after"))
		ReflectionHelpers.setField(fragment, "mReadyToDownloadMore", true)
		ReflectionHelpers.setField(fragment, "mTimestamp", TimestampUTC.now())
		ReflectionHelpers.callInstanceMethod<Any?>(fragment, "onLoadMoreItemsCheck")
		idle()
	}

	private fun click(menu: AlertDialog, position: Int = 0) {
		menu.listView.performItemClick(null, position, menu.listView.adapter.getItemId(position))
		idle()
	}

	private fun text(view: View): String = buildString {
		if(view is TextView) append(view.text)
		if(view is ViewGroup) for(index in 0 until view.childCount) append(text(view.getChildAt(index)))
	}

	private inline fun <reified T : View> find(view: View): T? {
		if(view is T) return view
		if(view is ViewGroup) for(index in 0 until view.childCount) {
			findView(view.getChildAt(index), T::class.java)?.let { return it }
		}
		return null
	}

	private fun <T : View> findView(view: View, type: Class<T>): T? {
		if(type.isInstance(view)) return type.cast(view)
		if(view is ViewGroup) for(index in 0 until view.childCount) {
			findView(view.getChildAt(index), type)?.let { return it }
		}
		return null
	}

	private fun complete(request: CacheRequest, body: String) {
		request.notifyDataStreamComplete(
			{ MemoryDataStream(body.toByteArray()).inputStream },
			TimestampUTC.now(), UUID.randomUUID(), false, "application/json")
		idle()
	}

	@Test
	fun restoredRelativeMultiRetainsAccountAfterDefaultSwitch() {
		val first = openListing()
		assertSame(alice, RecordingCache.requests.single().user)
		val state = first.onSaveInstanceState()
		switchDefault()
		openListing(state)
		assertSame(alice, RecordingCache.requests.last().user)
		assertEquals("Alice", state.getString("listingAccount"))
	}

	@Test
	fun paginationKeepsFirstPageAccountAfterDefaultSwitch() {
		val fragment = openListing()
		switchDefault()
		nextPage(fragment)
		assertEquals(2, RecordingCache.requests.size)
		assertTrue(RecordingCache.requests.last().url.value.contains("after=t3_after"))
		assertTrue(RecordingCache.requests.all { it.user === alice })
	}

	@Test
	fun removedRestoredAccountCannotFallBackToDefaultOrStartRequest() {
		val state = openListing().onSaveInstanceState()
		removeAlice()
		val before = RecordingCache.requests.size
		openListing(state)
		assertTrue(activity.isFinishing)
		assertEquals(before, RecordingCache.requests.size)
	}

	@Test
	fun removedAccountCannotLoadAnotherPage() {
		val fragment = openListing()
		removeAlice()
		nextPage(fragment)
		assertEquals(1, RecordingCache.requests.size)
	}

	@Test
	fun replacedCredentialsCannotLoadAnotherPage() {
		val fragment = openListing()
		val replacement = RedditAccount("Alice", RedditOAuth.RefreshToken("replacement"), 1, "client")
		ReflectionHelpers.setField(accounts, "accountsCache", arrayListOf(replacement, bob))
		nextPage(fragment)
		assertEquals(1, RecordingCache.requests.size)
	}

	@Test
	fun mainMenuNavigationNamesCapturedOwnerAfterDefaultSwitch() {
		var selected: PostListingURL? = null
		val listener = object : MainMenuSelectionListener {
			override fun onSelected(type: Int) {}
			override fun onSelected(url: PostListingURL) { selected = url }
		}
		val manager = MainMenuListingManager(activity, listener, alice)
		val item = ReflectionHelpers.callInstanceMethod<GroupedRecyclerViewItemListItemView>(
			manager, "makeMultiredditItem",
			ReflectionHelpers.ClassParameter.from(String::class.java, "news"),
			ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType!!, false))
		val holder = item.onCreateViewHolder(FrameLayout(activity))
		item.onBindViewHolder(holder)
		switchDefault()
		ReflectionHelpers.getField<View>(holder.itemView, "mMainLink").performClick()
		assertEquals("alice", selected!!.asMultiredditPostListURL().username)
		assertEquals("news", selected!!.asMultiredditPostListURL().name)
		assertEquals("/user/alice/m/news/.json", selected!!.generateJsonUri().path)
	}

	@Test
	fun combinationMenuStopsWithHostAndStaleClickCannotOpenInput() {
		val menu = MultiredditActionMenus.showCombination(activity, alice, combination)!!
		controller.pause().stop()
		idle()
		assertFalse(menu.isShowing)
		controller.start().resume()
		click(menu)
		assertSame(menu, ShadowDialog.getLatestDialog())
		assertTrue(RecordingCache.requests.isEmpty())
	}

	@Test
	fun multiMenuStopsWithHostAndStaleClickCannotOpenConfirmation() {
		val menu = MultiredditActionMenus.showMultireddit(activity, alice, target, null)!!
		controller.pause().stop()
		idle()
		assertFalse(menu.isShowing)
		controller.start().resume()
		click(menu)
		assertSame(menu, ShadowDialog.getLatestDialog())
	}

	@Test
	fun dismissedMenuCannotOpenChildWhileHostStillActive() {
		val menu = MultiredditActionMenus.showCombination(activity, alice, combination)!!
		menu.dismiss()
		idle()
		click(menu)
		assertSame(menu, ShadowDialog.getLatestDialog())
	}

	@Test
	fun menuCannotOpenChildAfterAccountWasRemoved() {
		val menu = MultiredditActionMenus.showMultireddit(activity, alice, target, null)!!
		removeAlice()
		click(menu)
		assertSame(menu, ShadowDialog.getLatestDialog())
	}

	@Test
	fun menuCannotOpenChildAfterCredentialsWereReplaced() {
		val menu = MultiredditActionMenus.showCombination(activity, alice, combination)!!
		val replacement = RedditAccount("Alice", RedditOAuth.RefreshToken("new"), 1, "client")
		ReflectionHelpers.setField(accounts, "accountsCache", arrayListOf(replacement, bob))
		click(menu)
		assertSame(menu, ShadowDialog.getLatestDialog())
	}

	@Test
	fun defaultSwitchKeepsOriginalCreateAccountInDescription() {
		val menu = MultiredditActionMenus.showCombination(activity, alice, combination)!!
		switchDefault()
		click(menu)
		val input = ShadowDialog.getLatestDialog() as AlertDialog
		assertNotSame(menu, input)
		assertTrue(text(input.window!!.decorView).contains("for Alice"))
	}

	@Test
	fun stoppedOrFinishingHostsCannotOpenEitherMenu() {
		controller.pause().stop()
		assertNull(MultiredditActionMenus.showCombination(activity, alice, combination))
		assertNull(MultiredditActionMenus.showMultireddit(activity, alice, target, null))
		controller.start().resume()
		activity.finish()
		assertNull(MultiredditActionMenus.showCombination(activity, alice, combination))
		assertNull(MultiredditActionMenus.showMultireddit(activity, alice, target, null))
	}

	@Test
	fun removedAccountsCannotOpenEitherMenu() {
		removeAlice()
		assertNull(MultiredditActionMenus.showCombination(activity, alice, combination))
		assertNull(MultiredditActionMenus.showMultireddit(activity, alice, target, null))
	}

	@Test
	fun ownMultiMenuIncludesBothConfiguredActions() {
		val menu = MultiredditActionMenus.showMultireddit(activity, alice, target, null)!!
		val titles = (0 until menu.listView.adapter.count).map { menu.listView.adapter.getItem(it) }
		assertEquals(listOf(activity.getString(R.string.delete_multireddit),
			activity.getString(R.string.remove_subreddit_from_multireddit)), titles)
	}

	@Test
	fun removeOnlyPreferenceLeavesMemberRemovalReachable() {
		General.getSharedPrefs(app).edit().putStringSet(app.getString(
			R.string.pref_menus_multireddits_context_items_key),
			setOf("remove_subreddit_from_multireddit")).apply()
		val menu = MultiredditActionMenus.showMultireddit(activity, alice, target, null)!!
		assertEquals(1, menu.listView.adapter.count)
		assertEquals(activity.getString(R.string.remove_subreddit_from_multireddit),
			menu.listView.adapter.getItem(0))
		click(menu)
		assertTrue(RecordingCache.requests.any {
			it.method == HTTPBackend.Method.GET && it.url.toUri().path == "/api/multi/user/alice/m/news"
		})
	}

	@Test
	fun removingThroughDisplayedHeaderForcesFreshPostListingRequest() {
		val intent = Intent(app, PostListingActivity::class.java)
			.setData(Uri.parse("https://www.reddit.com/user/alice/m/news"))
		val host = Robolectric.buildActivity(PostListingActivity::class.java, intent)
			.create().start().resume().visible()
		listingHosts.add(host)
		idle()
		val decor = host.get().window.decorView
		decor.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
			View.MeasureSpec.makeMeasureSpec(1800, View.MeasureSpec.EXACTLY))
		decor.layout(0, 0, 1080, 1800)
		idle()
		val initial = RecordingCache.requests.single { it.fileType == Constants.FileType.POST_LIST }
		assertTrue(requireNotNull(find<PostListingHeader>(decor)).performLongClick())
		idle()
		val menu = ShadowDialog.getLatestDialog() as AlertDialog
		val remove = (0 until menu.listView.adapter.count).single {
			menu.listView.adapter.getItem(it) == host.get().getString(
				R.string.remove_subreddit_from_multireddit)
		}
		click(menu, remove)
		val dialog = ShadowDialog.getLatestDialog() as AlertDialog
		val members = RecordingCache.requests.last { it.method == HTTPBackend.Method.GET
			&& it.url.toUri().path == "/api/multi/user/alice/m/news" }
		assertFalse(members.cache)
		complete(members, """{"data":{"subreddits":[{"name":"android"}]}}""")
		requireNotNull(find<AutoCompleteTextView>(dialog.window!!.decorView)).setText("android")
		dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
		val mutation = RecordingCache.requests.last { it.method == HTTPBackend.Method.DELETE }
		assertSame(alice, mutation.user)
		assertEquals("/api/multi/user/alice/m/news/r/android", mutation.url.toUri().path)
		complete(mutation, "")
		val reload = RecordingCache.requests.last { it.fileType == Constants.FileType.POST_LIST }
		assertNotSame(initial, reload)
		assertSame(DownloadStrategyAlways.INSTANCE, reload.downloadStrategy)
		assertSame(alice, reload.user)
		assertNull(reload.requestSession)
		assertNull(Shadows.shadowOf(host.get()).nextStartedActivity)
	}
}

package org.quantumbadger.redreader.fragments

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.Lifecycle
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.quantumbadger.redreader.account.RedditAccount
import org.quantumbadger.redreader.account.RedditAccountManager
import org.quantumbadger.redreader.activities.ChangelogActivity
import org.quantumbadger.redreader.adapters.MainMenuListingManager
import org.quantumbadger.redreader.adapters.MainMenuSelectionListener
import org.quantumbadger.redreader.cache.CacheManager
import org.quantumbadger.redreader.cache.CacheRequest
import org.quantumbadger.redreader.common.General
import org.quantumbadger.redreader.common.PrefsUtility
import org.quantumbadger.redreader.common.SharedPrefsWrapper
import org.quantumbadger.redreader.common.time.TimestampUTC
import org.quantumbadger.redreader.reddit.api.MultiredditActionMenus
import org.quantumbadger.redreader.reddit.api.MultiredditTarget
import org.quantumbadger.redreader.reddit.api.RedditOAuth
import org.quantumbadger.redreader.reddit.kthings.RedditIdAndType
import org.quantumbadger.redreader.reddit.url.PostListingURL
import org.quantumbadger.redreader.reddit.url.SubredditPostListURL
import org.quantumbadger.redreader.views.list.GroupedRecyclerViewItemListItemView
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.LooperMode
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowDialog
import org.robolectric.util.ReflectionHelpers
import java.util.concurrent.atomic.AtomicReference

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

	private fun click(menu: AlertDialog) {
		menu.listView.performItemClick(null, 0, menu.listView.adapter.getItemId(0))
		idle()
	}

	private fun text(view: View): String = buildString {
		if(view is TextView) append(view.text)
		if(view is ViewGroup) for(index in 0 until view.childCount) append(text(view.getChildAt(index)))
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
}

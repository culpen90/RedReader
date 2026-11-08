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

package org.quantumbadger.redreader.fragments

import android.content.SharedPreferences
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.AutoCompleteTextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.quantumbadger.redreader.R
import org.quantumbadger.redreader.account.RedditAccount
import org.quantumbadger.redreader.account.RedditAccountManager
import org.quantumbadger.redreader.cache.CacheRequest
import org.quantumbadger.redreader.cache.CacheRequestCallbacks
import org.quantumbadger.redreader.common.General
import org.quantumbadger.redreader.common.PrefsUtility
import org.quantumbadger.redreader.common.RRError
import org.quantumbadger.redreader.common.SharedPrefsWrapper
import org.quantumbadger.redreader.common.UriString
import org.quantumbadger.redreader.http.HTTPBackend
import org.quantumbadger.redreader.reddit.api.MultiredditApi
import org.quantumbadger.redreader.reddit.api.MultiredditTarget
import org.quantumbadger.redreader.reddit.api.RedditOAuth
import org.quantumbadger.redreader.reddit.things.SubredditCanonicalId
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AddToMultiredditDialogTest {

	private val app get() = RuntimeEnvironment.getApplication()
	private val alice = RedditAccount("Alice", RedditOAuth.RefreshToken("alice"), 1, "client")
	private val bob = RedditAccount("Bob", RedditOAuth.RefreshToken("bob"), 2, "client")
	private val subreddit = SubredditCanonicalId("android")
	private lateinit var controller: ActivityController<AppCompatActivity>
	private lateinit var accounts: RedditAccountManager
	private val activity get() = controller.get()

	@Before
	fun setUp() {
		ReflectionHelpers.getStaticField<AtomicReference<SharedPrefsWrapper>>(
			General::class.java, "mPrefs"
		).set(null)
		val prefs = General.getSharedPrefs(app)
		ReflectionHelpers.getField<SharedPreferences>(prefs, "mPrefs").edit().clear().commit()
		ReflectionHelpers.setStaticField(PrefsUtility::class.java, "sharedPrefs", prefs)
		ReflectionHelpers.setStaticField(PrefsUtility::class.java, "mRes", app.resources)
		ReflectionHelpers.setStaticField(PrefsUtility::class.java, "appContext", app)
		ReflectionHelpers.setStaticField(RedditAccountManager::class.java, "singleton", null)
		accounts = RedditAccountManager.getInstance(app)
		accounts.addAccount(alice)
		accounts.setDefaultAccount(alice)
		controller = Robolectric.buildActivity(AppCompatActivity::class.java)
		controller.get().setTheme(R.style.RR_Dark)
		controller.setup()
	}

	@After
	fun tearDown() {
		if(activity.lifecycle.currentState == Lifecycle.State.RESUMED) controller.pause()
		if(activity.lifecycle.currentState == Lifecycle.State.STARTED) controller.stop()
		if(activity.lifecycle.currentState != Lifecycle.State.DESTROYED) controller.destroy()
		accounts.close()
		idle()
	}

	private fun idle() = Shadows.shadowOf(Looper.getMainLooper()).idle()

	private fun input(view: View): AutoCompleteTextView? {
		if(view is AutoCompleteTextView) return view
		if(view is ViewGroup) {
			for(index in 0 until view.childCount) {
				input(view.getChildAt(index))?.let { return it }
			}
		}
		return null
	}

	private inner class Operations : AddToMultiredditDialog.Operations {
		var loads = 0
		val addedAccounts = mutableListOf<RedditAccount>()
		val targets = mutableListOf<MultiredditTarget>()
		val refreshed = mutableListOf<RedditAccount>()
		val requests = mutableListOf<CacheRequest>()
		lateinit var namesListener: MultiredditApi.MembersListener
		lateinit var mutationListener: MultiredditApi.ActionListener

		private fun inert(account: RedditAccount): CacheRequest {
			val request = CacheRequest.newUncachedRequest(UriString("https://example.invalid/fixture"),
				account, HTTPBackend.Method.GET, null, app, object : CacheRequestCallbacks {
					override fun onFailure(error: RRError) { }
				})
			requests.add(request)
			return request
		}

		override fun loadNames(account: RedditAccount,
			listener: MultiredditApi.MembersListener): CacheRequest {
			assertSame(alice, account)
			loads++
			namesListener = listener
			return inert(account)
		}

		override fun add(account: RedditAccount, target: MultiredditTarget,
			source: SubredditCanonicalId, listener: MultiredditApi.ActionListener): CacheRequest {
			assertEquals(subreddit, source)
			addedAccounts.add(account)
			targets.add(target)
			mutationListener = listener
			return inert(account)
		}

		override fun refresh(account: RedditAccount) { refreshed.add(account) }
	}

	private fun open(operations: Operations): Pair<AlertDialog, AutoCompleteTextView> {
		val dialog = AddToMultiredditDialog.show(activity, alice, subreddit, operations)!!
		idle()
		return dialog to input(dialog.window!!.decorView)!!
	}

	@Test
	fun loadingIsSafeBeforeAnySubscriptionsHaveArrived() {
		val operations = Operations()
		val (dialog, input) = open(operations)
		assertEquals(1, operations.loads)
		assertFalse(dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled)
		input.onEditorAction(EditorInfo.IME_ACTION_UNSPECIFIED)
		input.onEditorAction(EditorInfo.IME_ACTION_GO)
		assertTrue(operations.addedAccounts.isEmpty())
		dialog.dismiss()
	}

	@Test
	fun loadedNamesAndSubmissionRemainBoundToCapturedAccount() {
		val operations = Operations()
		val (dialog, input) = open(operations)
		accounts.addAccount(bob)
		accounts.setDefaultAccount(bob)
		assertEquals(bob, accounts.defaultAccount)
		operations.namesListener.onSuccess(listOf("news"))
		input.setText("NEWS")
		dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
		assertEquals(1, operations.addedAccounts.size)
		assertSame(alice, operations.addedAccounts.single())
		assertEquals("/user/alice/m/news", operations.targets.single().getDisplayPath())
		assertFalse(input.isEnabled)
		assertFalse(dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled)
		operations.mutationListener.onSuccess()
		idle()
		assertSame(alice, operations.refreshed.single())
		assertFalse(dialog.isShowing)
	}

	@Test
	fun invalidOrUnownedInputStaysEditableWithoutSendingAnything() {
		val operations = Operations()
		val (dialog, input) = open(operations)
		operations.namesListener.onSuccess(listOf("news"))
		input.setText("someone_else")
		dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
		assertTrue(dialog.isShowing)
		assertEquals("someone_else", input.text.toString())
		assertNotNull(input.error)
		assertTrue(operations.addedAccounts.isEmpty())
		dialog.dismiss()
	}

	@Test
	fun imeAndRepeatedSubmitCannotDuplicateAnInFlightWrite() {
		val operations = Operations()
		val (dialog, input) = open(operations)
		operations.namesListener.onSuccess(listOf("news"))
		input.setText("news")
		input.onEditorAction(EditorInfo.IME_ACTION_GO)
		input.onEditorAction(EditorInfo.IME_ACTION_GO)
		dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
		assertEquals(1, operations.addedAccounts.size)
		dialog.dismiss()
	}

	@Test
	fun hardwareEnterSubmitsOnReleaseOnly() {
		val operations = Operations()
		val (dialog, input) = open(operations)
		operations.namesListener.onSuccess(listOf("news"))
		input.setText("news")
		input.requestFocus()
		input.onKeyDown(KeyEvent.KEYCODE_ENTER, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
		assertTrue(operations.addedAccounts.isEmpty())
		input.onKeyUp(KeyEvent.KEYCODE_ENTER, KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
		assertEquals(1, operations.addedAccounts.size)
		dialog.dismiss()
	}

	@Test
	fun serverFailurePreservesInputAndAllowsOneRetry() {
		val operations = Operations()
		val (dialog, input) = open(operations)
		operations.namesListener.onSuccess(listOf("news"))
		input.setText("news")
		input.onEditorAction(EditorInfo.IME_ACTION_GO)
		operations.mutationListener.onFailure(RRError("Failure", "Try again", false))
		assertTrue(dialog.isShowing)
		assertTrue(input.isEnabled)
		assertEquals("news", input.text.toString())
		assertNotNull(input.error)
		input.onEditorAction(EditorInfo.IME_ACTION_GO)
		assertEquals(2, operations.addedAccounts.size)
		dialog.dismiss()
	}

	@Test
	fun failedNameReadHasRetryAndDoesNotDestroyTypedText() {
		val operations = Operations()
		val (dialog, input) = open(operations)
		operations.namesListener.onFailure(RRError("Failure", "Offline", false))
		input.setText("news")
		dialog.getButton(AlertDialog.BUTTON_NEUTRAL).performClick()
		assertEquals(2, operations.loads)
		operations.namesListener.onSuccess(listOf("news"))
		assertEquals("news", input.text.toString())
		assertTrue(dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled)
		dialog.dismiss()
	}

	@Test
	fun stoppingHostCancelsReadAndIgnoresItsLateCallback() {
		val operations = Operations()
		val (dialog, _) = open(operations)
		controller.pause().stop()
		idle()
		assertFalse(dialog.isShowing)
		assertTrue(ReflectionHelpers.getField<Boolean>(operations.requests.single(), "cancelled"))
		operations.namesListener.onSuccess(listOf("news"))
		assertTrue(operations.addedAccounts.isEmpty())
	}

	@Test
	fun stoppingDuringMutationCancelsAndSuppressesLateUiAndRefresh() {
		val operations = Operations()
		val (dialog, input) = open(operations)
		operations.namesListener.onSuccess(listOf("news"))
		input.setText("news")
		input.onEditorAction(EditorInfo.IME_ACTION_GO)
		controller.pause().stop()
		idle()
		assertFalse(dialog.isShowing)
		assertTrue(operations.requests.all { ReflectionHelpers.getField<Boolean>(it, "cancelled") })
		operations.mutationListener.onSuccess()
		assertTrue(operations.refreshed.isEmpty())
	}

	@Test
	fun anonymousAndCombinedFeedsCannotOpenAnAddWriteFlow() {
		val operations = Operations()
		assertNull(AddToMultiredditDialog.show(activity,
			RedditAccount("", null, 0, null), subreddit, operations))
		assertNull(AddToMultiredditDialog.show(activity,
			alice, SubredditCanonicalId("android+java"), operations))
		assertEquals(0, operations.loads)
	}

	@Test
	fun removedOrReplacedAccountsCannotOpenTheDialog() {
		val operations = Operations()
		accounts.deleteAccount(alice)
		assertNull(AddToMultiredditDialog.show(activity, alice, subreddit, operations))
		accounts.addAccount(RedditAccount("Alice", RedditOAuth.RefreshToken("new"), 1, "client"))
		assertNull(AddToMultiredditDialog.show(activity, alice, subreddit, operations))
		assertEquals(0, operations.loads)
	}

	@Test
	fun logoutBeforeSubmitCancelsTheDialogWithoutUsingTheOldToken() {
		val operations = Operations()
		val (dialog, input) = open(operations)
		operations.namesListener.onSuccess(listOf("news"))
		input.setText("news")
		accounts.deleteAccount(alice)
		dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
		idle()
		assertFalse(dialog.isShowing)
		assertTrue(operations.addedAccounts.isEmpty())
		assertTrue(operations.requests.all { ReflectionHelpers.getField<Boolean>(it, "cancelled") })
	}

	@Test
	fun replacedCredentialsBeforeSubmitCannotUseTheCapturedToken() {
		val operations = Operations()
		val (dialog, input) = open(operations)
		operations.namesListener.onSuccess(listOf("news"))
		input.setText("news")
		accounts.addAccount(RedditAccount("Alice", RedditOAuth.RefreshToken("new"), 1, "client"))
		input.onEditorAction(EditorInfo.IME_ACTION_GO)
		idle()
		assertFalse(dialog.isShowing)
		assertTrue(operations.addedAccounts.isEmpty())
	}

	@Test
	fun accountRemovalWhileLoadingSuppressesLateChoicesAndRetry() {
		val operations = Operations()
		val (dialog, _) = open(operations)
		accounts.deleteAccount(alice)
		operations.namesListener.onSuccess(listOf("news"))
		idle()
		assertFalse(dialog.isShowing)
		assertTrue(operations.requests.all { ReflectionHelpers.getField<Boolean>(it, "cancelled") })
		assertTrue(operations.addedAccounts.isEmpty())
	}

	@Test
	fun replacedCredentialsDuringMutationSuppressSuccessRefreshAndToast() {
		val operations = Operations()
		val (dialog, input) = open(operations)
		operations.namesListener.onSuccess(listOf("news"))
		input.setText("news")
		input.onEditorAction(EditorInfo.IME_ACTION_GO)
		val toastCount = org.robolectric.shadows.ShadowToast.shownToastCount()
		accounts.addAccount(RedditAccount("Alice", RedditOAuth.RefreshToken("new"), 1, "client"))
		operations.mutationListener.onSuccess()
		idle()
		assertFalse(dialog.isShowing)
		assertTrue(operations.refreshed.isEmpty())
		assertEquals(toastCount, org.robolectric.shadows.ShadowToast.shownToastCount())
		assertTrue(operations.requests.all { ReflectionHelpers.getField<Boolean>(it, "cancelled") })
	}

	@Test
	fun retryLoadRejectsOldAndDuplicateCompletionCallbacks() {
		val operations = Operations()
		val (dialog, input) = open(operations)
		val old = operations.namesListener
		old.onFailure(RRError("Failure", "Offline", false))
		dialog.getButton(AlertDialog.BUTTON_NEUTRAL).performClick()
		operations.namesListener.onSuccess(listOf("news"))
		old.onSuccess(listOf("old"))
		old.onFailure(RRError("Old", "Outdated", false))
		operations.namesListener.onSuccess(listOf("duplicate"))
		input.setText("news")
		input.onEditorAction(EditorInfo.IME_ACTION_GO)
		assertEquals("news", operations.targets.single().name)
		dialog.dismiss()
	}

	@Test
	fun retryMutationIgnoresTheFirstRequestsLateSuccessAndFailure() {
		val operations = Operations()
		val (dialog, input) = open(operations)
		operations.namesListener.onSuccess(listOf("news"))
		input.setText("news")
		input.onEditorAction(EditorInfo.IME_ACTION_GO)
		val old = operations.mutationListener
		old.onFailure(RRError("Failure", "Retry", false))
		input.onEditorAction(EditorInfo.IME_ACTION_GO)
		old.onSuccess()
		old.onFailure(RRError("Old", "Outdated", false))
		assertTrue(dialog.isShowing)
		assertFalse(input.isEnabled)
		assertTrue(operations.refreshed.isEmpty())
		operations.mutationListener.onSuccess()
		idle()
		assertFalse(dialog.isShowing)
		assertEquals(listOf(alice), operations.refreshed)
	}
}

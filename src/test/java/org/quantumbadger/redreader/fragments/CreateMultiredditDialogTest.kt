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
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.quantumbadger.redreader.R
import org.quantumbadger.redreader.account.RedditAccount
import org.quantumbadger.redreader.account.RedditAccountManager
import org.quantumbadger.redreader.activities.ChangelogActivity
import org.quantumbadger.redreader.common.General
import org.quantumbadger.redreader.common.PrefsUtility
import org.quantumbadger.redreader.common.RRError
import org.quantumbadger.redreader.common.SharedPrefsWrapper
import org.quantumbadger.redreader.reddit.api.MultiredditActionMenus
import org.quantumbadger.redreader.reddit.api.MultiredditApi
import org.quantumbadger.redreader.reddit.api.MultiredditTarget
import org.quantumbadger.redreader.reddit.things.SubredditCanonicalId
import org.quantumbadger.redreader.reddit.url.SubredditPostListURL
import org.quantumbadger.redreader.views.PostListingHeader
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import org.robolectric.util.ReflectionHelpers
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CreateMultiredditDialogTest {

	private val app get() = RuntimeEnvironment.getApplication()
	private val account = RedditAccount("Alice", null, 0, "client")
	private lateinit var prefs: SharedPreferences
	private val controller by lazy { Robolectric.buildActivity(ChangelogActivity::class.java).setup() }
	private val activity get() = controller.get()
	private val dialogs = mutableListOf<AlertDialog>()

	@Before
	fun preferences() {
		ReflectionHelpers.getStaticField<AtomicReference<SharedPrefsWrapper>>(
			General::class.java, "mPrefs"
		).set(null)
		val wrapped = General.getSharedPrefs(app)
		prefs = ReflectionHelpers.getField(wrapped, "mPrefs")
		prefs.edit().clear().commit()
		ReflectionHelpers.setStaticField(PrefsUtility::class.java, "sharedPrefs", wrapped)
		ReflectionHelpers.setStaticField(PrefsUtility::class.java, "mRes", app.resources)
		ReflectionHelpers.setStaticField(PrefsUtility::class.java, "appContext", app)
		ReflectionHelpers.setStaticField(RedditAccountManager::class.java, "singleton", null)
		val accounts = RedditAccountManager.getInstance(app)
		ReflectionHelpers.setField(accounts, "accountsCache", arrayListOf(account))
		ReflectionHelpers.setField(accounts, "defaultAccountCache", account)
	}

	@After
	fun close() {
		dialogs.forEach { it.dismiss() }
		idle()
		controller.pause().stop().destroy()
	}

	private fun idle() { Shadows.shadowOf(Looper.getMainLooper()).idle() }

	private class Handle : MultiredditDialogs.Pending {
		var cancelled = false
		override fun cancel() { cancelled = true }
	}

	private class FakeBackend : MultiredditDialogs.Backend {
		data class Call(
			val account: RedditAccount,
			val target: MultiredditTarget,
			val members: List<SubredditCanonicalId>,
			val listener: MultiredditApi.ActionListener,
			val handle: Handle
		)
		val calls = mutableListOf<Call>()
		var completeSynchronously = false
		override fun create(
			account: RedditAccount,
			target: MultiredditTarget,
			subreddits: Collection<SubredditCanonicalId>,
			listener: MultiredditApi.ActionListener
		): MultiredditDialogs.Pending {
			val handle = Handle()
			calls.add(Call(account, target, subreddits.toList(), listener, handle))
			if(completeSynchronously) listener.onSuccess()
			return handle
		}
	}

	private fun input(view: View): EditText? {
		if(view is EditText) return view
		if(view is ViewGroup) for(i in 0 until view.childCount) {
			input(view.getChildAt(i))?.let { return it }
		}
		return null
	}

	private fun show(
		backend: FakeBackend,
		members: Collection<SubredditCanonicalId> = listOf(SubredditCanonicalId("android")),
		user: RedditAccount = account
	): Pair<AlertDialog, EditText> {
		val dialog = requireNotNull(MultiredditDialogs.showCreate(activity, user, members, backend))
		dialogs.add(dialog)
		idle()
		return dialog to input(dialog.window!!.decorView)!!
	}

	@Test
	fun invalidNameRetainsInputAndNeverSubmits() {
		val backend = FakeBackend()
		val (dialog, input) = show(backend)
		listOf("", " ", "bad/name", "a b", "x".repeat(51)).forEach {
			input.setText(it)
			dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
			assertTrue(dialog.isShowing)
			assertEquals(it, input.text.toString())
			assertNotNull(input.error)
		}
		assertTrue(backend.calls.isEmpty())
	}

	@Test
	fun submissionCapturesAccountAndMemberSnapshotAndPreventsDuplicates() {
		val backend = FakeBackend()
		val source = mutableListOf(SubredditCanonicalId("android"), SubredditCanonicalId("linux"))
		val (dialog, input) = show(backend, source)
		source.clear()
		input.setText("news")
		input.onEditorAction(EditorInfo.IME_ACTION_GO)
		dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
		input.onEditorAction(EditorInfo.IME_ACTION_DONE)
		assertEquals(1, backend.calls.size)
		assertSame(account, backend.calls.single().account)
		assertEquals("/user/alice/m/news", backend.calls.single().target.getDisplayPath())
		assertEquals(listOf("android", "linux"), backend.calls.single().members
			.map { it.displayNameLowercase })
		assertFalse(input.isEnabled)
		assertFalse(dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled)
	}

	@Test
	fun failureKeepsInputAndAllowsRetryButIgnoresOldCallbacks() {
		val backend = FakeBackend()
		val (dialog, input) = show(backend)
		input.setText("news")
		dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
		val first = backend.calls.single()
		first.listener.onFailure(RRError(message = "Name already exists", reportable = false))
		assertTrue(dialog.isShowing)
		assertTrue(input.isEnabled)
		assertEquals("news", input.text.toString())
		input.setText("news2")
		dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
		assertEquals(2, backend.calls.size)
		first.listener.onSuccess()
		assertTrue(dialog.isShowing)
		backend.calls.last().listener.onSuccess()
		assertFalse(dialog.isShowing)
	}

	@Test
	fun backgroundingCancelsPendingWriteAndIgnoresLateResponse() {
		val backend = FakeBackend()
		val (dialog, input) = show(backend)
		input.setText("news")
		dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
		val call = backend.calls.single()
		controller.pause().stop()
		idle()
		assertFalse(dialog.isShowing)
		assertTrue(call.handle.cancelled)
		call.listener.onFailure(RRError(message = "Too late"))
		call.listener.onSuccess()
		assertFalse(dialog.isShowing)
		assertSame(dialog, ShadowDialog.getLatestDialog())
	}

	@Test
	fun synchronousSuccessDoesNotRetainReturnedRequest() {
		val backend = FakeBackend().apply { completeSynchronously = true }
		val (dialog, input) = show(backend)
		input.setText("news")
		dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
		assertFalse(dialog.isShowing)
		assertTrue(backend.calls.single().handle.cancelled)
	}

	@Test
	fun anonymousAccountCannotSubmit() {
		val backend = FakeBackend()
		val (dialog, input) = show(backend, user = RedditAccount("", null, 0, null))
		input.setText("news")
		dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
		assertNotNull(input.error)
		assertTrue(backend.calls.isEmpty())
	}

	@Test
	fun replacedCredentialsCannotSubmitCapturedDialog() {
		val backend = FakeBackend()
		val (dialog, input) = show(backend)
		val accounts = RedditAccountManager.getInstance(app)
		ReflectionHelpers.setField(accounts, "accountsCache", arrayListOf(
			RedditAccount("Alice", null, 0, "replacement-client")))
		input.setText("news")
		dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
		assertTrue(backend.calls.isEmpty())
	}

	@Test
	fun editorNullAndHardwareEnterAreHandledWithoutDoubleSubmit() {
		var submissions = 0
		val submit = Runnable { submissions++ }
		assertFalse(MultiredditDialogs.handleEditor(EditorInfo.IME_ACTION_NONE, null, submit))
		assertTrue(MultiredditDialogs.handleEditor(EditorInfo.IME_ACTION_GO, null, submit))
		assertTrue(MultiredditDialogs.handleEditor(EditorInfo.IME_NULL,
			KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER), submit))
		assertTrue(MultiredditDialogs.handleEditor(EditorInfo.IME_NULL,
			KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER), submit))
		assertEquals(2, submissions)
	}

	@Test
	fun combinedListingKeepsAllOrdinaryMembersAndDeduplicates() {
		val url = SubredditPostListURL.getSubreddit("android+linux+android").asSubredditPostListURL()
		assertEquals(listOf("android", "linux"), MultiredditActionMenus.combinationSubreddits(url)
			.map { it.displayNameLowercase })
	}

	@Test
	fun combinationHeaderActionRespectsAccountAndMenuPreference() {
		val url = SubredditPostListURL.getSubreddit("android+linux").asSubredditPostListURL()
		val header = PostListingHeader(activity, "Android and Linux", "android+linux", url, null)
		MultiredditActionMenus.attachToHeader(activity, account, url, header)
		assertTrue(header.performLongClick())
		val menu = ShadowDialog.getLatestDialog() as AlertDialog
		dialogs.add(menu)
		assertEquals(activity.getString(R.string.create_multireddit), menu.listView.adapter.getItem(0))
		menu.dismiss()
		prefs.edit().putStringSet(activity.getString(
			R.string.pref_menus_subreddit_combinations_context_items_key), emptySet()).commit()
		assertNull(MultiredditActionMenus.showCombination(activity, account, url))
		assertNull(MultiredditActionMenus.showCombination(activity,
			RedditAccount("", null, 0, null), url))
	}
}

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
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.AutoCompleteTextView
import androidx.appcompat.app.AlertDialog
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.quantumbadger.redreader.account.RedditAccount
import org.quantumbadger.redreader.account.RedditAccountManager
import org.quantumbadger.redreader.activities.ChangelogActivity
import org.quantumbadger.redreader.common.General
import org.quantumbadger.redreader.common.PrefsUtility
import org.quantumbadger.redreader.common.RRError
import org.quantumbadger.redreader.common.SharedPrefsWrapper
import org.quantumbadger.redreader.reddit.api.MultiredditApi
import org.quantumbadger.redreader.reddit.api.MultiredditTarget
import org.quantumbadger.redreader.reddit.things.SubredditCanonicalId
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RemoveFromMultiredditDialogTest {
	private val app get() = RuntimeEnvironment.getApplication()
	private val account = RedditAccount("Alice", null, 0, "client")
	private val target get() = MultiredditTarget.forName(account, "news")
	private val controller by lazy { Robolectric.buildActivity(ChangelogActivity::class.java).setup() }
	private val activity get() = controller.get()
	private val dialogs = mutableListOf<AlertDialog>()

	@Before
	fun preferences() {
		ReflectionHelpers.getStaticField<AtomicReference<SharedPrefsWrapper>>(
			General::class.java, "mPrefs").set(null)
		val wrapped = General.getSharedPrefs(app)
		ReflectionHelpers.getField<SharedPreferences>(wrapped, "mPrefs").edit().clear().commit()
		ReflectionHelpers.setStaticField(PrefsUtility::class.java, "sharedPrefs", wrapped)
		ReflectionHelpers.setStaticField(PrefsUtility::class.java, "mRes", app.resources)
		ReflectionHelpers.setStaticField(PrefsUtility::class.java, "appContext", app)
		RedditAccountManager.getInstance(app).addAccount(account)
	}

	@After
	fun close() {
		dialogs.forEach { it.dismiss() }
		idle()
		controller.pause().stop().destroy()
	}

	private fun idle() { Shadows.shadowOf(Looper.getMainLooper()).idle() }
	private fun input(view: View): AutoCompleteTextView? {
		if(view is AutoCompleteTextView) return view
		if(view is ViewGroup) for(i in 0 until view.childCount) {
			input(view.getChildAt(i))?.let { return it }
		}
		return null
	}

	private class Handle : MultiredditDialogs.Pending {
		var cancelled = false
		override fun cancel() { cancelled = true }
	}

	private class Fake : RemoveFromMultiredditDialog.Operations {
		data class Read(val account: RedditAccount, val target: MultiredditTarget,
			val listener: MultiredditApi.MembersListener, val handle: Handle)
		data class Write(val account: RedditAccount, val target: MultiredditTarget,
			val subreddit: SubredditCanonicalId,
			val listener: MultiredditApi.ActionListener, val handle: Handle)
		val reads = mutableListOf<Read>()
		val writes = mutableListOf<Write>()
		val refreshed = mutableListOf<RedditAccount>()
		override fun refresh(account: RedditAccount) { refreshed.add(account) }
		override fun members(account: RedditAccount, target: MultiredditTarget,
			listener: MultiredditApi.MembersListener): MultiredditDialogs.Pending {
			val handle = Handle()
			reads.add(Read(account, target, listener, handle))
			return handle
		}
		override fun remove(account: RedditAccount, target: MultiredditTarget,
			subreddit: SubredditCanonicalId,
			listener: MultiredditApi.ActionListener): MultiredditDialogs.Pending {
			val handle = Handle()
			writes.add(Write(account, target, subreddit, listener, handle))
			return handle
		}
	}

	private fun show(fake: Fake, after: Runnable? = null): AlertDialog {
		val dialog = requireNotNull(RemoveFromMultiredditDialog.show(
			activity, account, target, after, fake))
		dialogs.add(dialog)
		idle()
		return dialog
	}

	@Test
	fun waitsForFreshMembersAndRejectsUnknownTypedName() {
		val fake = Fake()
		val dialog = show(fake)
		val field = input(dialog.window!!.decorView)!!
		assertFalse(field.isEnabled)
		dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
		assertTrue(fake.writes.isEmpty())
		fake.reads.single().listener.onSuccess(listOf("android", "java"))
		field.setText("not_a_member")
		dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
		assertNotNull(field.error)
		assertTrue(fake.writes.isEmpty())
		field.setText(" /r/AnDrOiD ")
		field.onEditorAction(EditorInfo.IME_ACTION_GO)
		assertEquals("android", fake.writes.single().subreddit.displayNameLowercase)
		assertEquals("/user/alice/m/news", fake.writes.single().target.getDisplayPath())
	}

	@Test
	fun loadingFailureRequiresRetryAndIgnoresOldGeneration() {
		val fake = Fake()
		val dialog = show(fake)
		val first = fake.reads.single()
		first.listener.onFailure(RRError(message = "Offline", reportable = false))
		assertFalse(dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled)
		dialog.getButton(AlertDialog.BUTTON_NEUTRAL).performClick()
		assertEquals(2, fake.reads.size)
		first.listener.onSuccess(listOf("stale"))
		assertFalse(dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled)
		fake.reads.last().listener.onSuccess(listOf("fresh"))
		val field = input(dialog.window!!.decorView)!!
		field.setText("stale")
		dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
		assertTrue(fake.writes.isEmpty())
		field.setText("fresh")
		dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
		assertEquals("fresh", fake.writes.single().subreddit.displayNameLowercase)
	}

	@Test
	fun emptyMultiCannotRemoveAndCanReload() {
		val fake = Fake()
		val dialog = show(fake)
		fake.reads.single().listener.onSuccess(emptyList())
		assertFalse(dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled)
		assertFalse(input(dialog.window!!.decorView)!!.isEnabled)
		dialog.getButton(AlertDialog.BUTTON_NEUTRAL).performClick()
		assertEquals(2, fake.reads.size)
	}

	@Test
	fun duplicateSubmitsAndImeAreSingleRequestAndSuccessRefreshesCapturedOwner() {
		val fake = Fake()
		var removed = 0
		val dialog = show(fake, Runnable { removed++ })
		fake.reads.single().listener.onSuccess(listOf("android"))
		val field = input(dialog.window!!.decorView)!!
		field.setText("android")
		val bob = RedditAccount("Bob", null, 1, "other-client")
		RedditAccountManager.getInstance(app).addAccount(bob)
		ReflectionHelpers.setField(RedditAccountManager.getInstance(app), "defaultAccountCache", bob)
		field.onEditorAction(EditorInfo.IME_ACTION_DONE)
		field.onEditorAction(EditorInfo.IME_ACTION_GO)
		dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
		assertEquals(1, fake.writes.size)
		assertSame(account, fake.writes.single().account)
		fake.writes.single().listener.onSuccess()
		idle()
		assertFalse(dialog.isShowing)
		assertEquals(listOf(account), fake.refreshed)
		assertEquals(1, removed)
	}

	@Test
	fun serverFailureKeepsSelectionAndLateSuccessCannotCompleteRetry() {
		val fake = Fake()
		val dialog = show(fake)
		fake.reads.single().listener.onSuccess(listOf("android"))
		val field = input(dialog.window!!.decorView)!!
		field.setText("android")
		dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
		val first = fake.writes.single()
		first.listener.onFailure(RRError(message = "Forbidden", reportable = false))
		assertTrue(dialog.isShowing)
		assertEquals("android", field.text.toString())
		dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
		first.listener.onSuccess()
		assertTrue(dialog.isShowing)
		fake.writes.last().listener.onSuccess()
		idle()
		assertFalse(dialog.isShowing)
	}

	@Test
	fun dismissDuringLoadCancelsAndLateListCannotEnableControls() {
		val fake = Fake()
		val dialog = show(fake)
		val first = fake.reads.single()
		dialog.dismiss()
		idle()
		assertTrue(first.handle.cancelled)
		first.listener.onSuccess(listOf("android"))
		assertFalse(dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled)
		assertTrue(fake.writes.isEmpty())
	}

	@Test
	fun stoppedHostCancelsMutationAndCannotNavigate() {
		val fake = Fake()
		var removed = 0
		val dialog = show(fake, Runnable { removed++ })
		fake.reads.single().listener.onSuccess(listOf("android"))
		input(dialog.window!!.decorView)!!.setText("android")
		dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
		val call = fake.writes.single()
		controller.pause().stop()
		idle()
		assertFalse(dialog.isShowing)
		assertTrue(call.handle.cancelled)
		call.listener.onSuccess()
		assertEquals(0, removed)
		assertTrue(fake.refreshed.isEmpty())
	}

	@Test
	fun anotherOwnersMultiIsNeverReadOrMutated() {
		val fake = Fake()
		val bob = RedditAccount("Bob", null, 0, "client")
		assertNull(RemoveFromMultiredditDialog.show(activity, account,
			MultiredditTarget.forName(bob, "news"), null, fake))
		assertTrue(fake.reads.isEmpty())
		assertTrue(fake.writes.isEmpty())
	}

	@Test
	fun accountRemovalBeforeSubmitCannotUseRevokedCredentials() {
		val fake = Fake()
		val dialog = show(fake)
		fake.reads.single().listener.onSuccess(listOf("android"))
		input(dialog.window!!.decorView)!!.setText("android")
		ReflectionHelpers.setField(RedditAccountManager.getInstance(app),
			"accountsCache", arrayListOf<RedditAccount>())
		dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
		assertTrue(fake.writes.isEmpty())
		assertFalse(dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled)
	}

	@Test
	fun malformedServerMemberNeverBecomesSelectable() {
		val fake = Fake()
		val dialog = show(fake)
		fake.reads.single().listener.onSuccess(listOf("/user/bob"))
		assertFalse(dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled)
		assertTrue(fake.writes.isEmpty())
	}
}

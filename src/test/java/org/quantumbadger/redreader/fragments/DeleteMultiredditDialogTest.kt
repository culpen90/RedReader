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
import android.widget.TextView
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
import org.quantumbadger.redreader.reddit.url.MultiredditPostListURL
import org.quantumbadger.redreader.views.PostListingHeader
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DeleteMultiredditDialogTest {
	private val app get() = RuntimeEnvironment.getApplication()
	private val account = RedditAccount("Alice", null, 0, "client")
	private val target get() = MultiredditTarget.forName(account, "news")
	private val controller by lazy { Robolectric.buildActivity(ChangelogActivity::class.java).setup() }
	private val activity get() = controller.get()
	private val dialogs = mutableListOf<AlertDialog>()

	@Before
	fun preferences() {
		ReflectionHelpers.getStaticField<AtomicReference<SharedPrefsWrapper>>(
			General::class.java, "mPrefs"
		).set(null)
		val wrapped = General.getSharedPrefs(app)
		ReflectionHelpers.getField<SharedPreferences>(wrapped, "mPrefs").edit().clear().commit()
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
			val listener: MultiredditApi.ActionListener,
			val handle: Handle
		)
		val calls = mutableListOf<Call>()
		override fun delete(
			account: RedditAccount,
			target: MultiredditTarget,
			listener: MultiredditApi.ActionListener
		): MultiredditDialogs.Pending {
			val handle = Handle()
			calls.add(Call(account, target, listener, handle))
			return handle
		}
	}

	private fun show(backend: FakeBackend, after: Runnable? = null): AlertDialog {
		val dialog = MultiredditDialogs.showDelete(activity, account, target, after, backend)
		dialogs.add(dialog)
		idle()
		return dialog
	}

	private fun allText(view: View): String = buildString {
		if(view is TextView) append(view.text)
		if(view is ViewGroup) for(i in 0 until view.childCount) append(allText(view.getChildAt(i)))
	}

	@Test
	fun confirmationNamesFullOwnerAndWaitsForExplicitConsent() {
		val backend = FakeBackend()
		val dialog = show(backend)
		assertTrue(allText(dialog.window!!.decorView).contains("/user/alice/m/news"))
		assertTrue(backend.calls.isEmpty())
		dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
		idle()
		assertTrue(backend.calls.isEmpty())
		assertFalse(dialog.isShowing)
	}

	@Test
	fun cannotDeleteOwnSameNamedMultiWhileViewingAnotherUsers() {
		val url = MultiredditPostListURL.getMultireddit("bob", "news").asMultiredditPostListURL()
		assertEquals("bob", url.username)
		assertEquals("news", url.name)
		assertFalse(url.generateJsonUri().encodedPath!!.contains("%2F"))
		val other = MultiredditTarget.fromListing(account, url)
		assertFalse(other.isOwnedBy(account))
		assertNull(MultiredditActionMenus.showMultireddit(activity, account, other, null))
		val backend = FakeBackend()
		val dialog = MultiredditDialogs.showDelete(activity, account, other, null, backend)
		dialogs.add(dialog)
		idle()
		assertTrue(dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.visibility != View.VISIBLE)
		assertTrue(backend.calls.isEmpty())
		val header = PostListingHeader(activity, "Bob news", "Bob", url, null)
		MultiredditActionMenus.attachToHeader(activity, account, url, header)
		assertFalse(header.isLongClickable)
	}

	@Test
	fun confirmedDeleteCapturesIdentityAndPreventsDuplicateRequests() {
		val backend = FakeBackend()
		var deleted = 0
		val dialog = show(backend, Runnable { deleted++ })
		dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
		dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
		assertEquals(1, backend.calls.size)
		assertSame(account, backend.calls.single().account)
		assertEquals("/user/alice/m/news", backend.calls.single().target.getDisplayPath())
		backend.calls.single().listener.onSuccess()
		assertFalse(dialog.isShowing)
		assertEquals(1, deleted)
	}

	@Test
	fun failureAllowsRetryWithoutChangingTarget() {
		val backend = FakeBackend()
		val dialog = show(backend)
		dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
		val first = backend.calls.single()
		first.listener.onFailure(RRError(message = "Forbidden", reportable = false))
		assertTrue(dialog.isShowing)
		assertTrue(dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled)
		dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
		first.listener.onSuccess()
		assertTrue(dialog.isShowing)
		assertEquals(first.target, backend.calls.last().target)
		backend.calls.last().listener.onSuccess()
		assertFalse(dialog.isShowing)
	}

	@Test
	fun stoppingHostCancelsAndLateSuccessCannotNavigate() {
		val backend = FakeBackend()
		var deleted = 0
		val dialog = show(backend, Runnable { deleted++ })
		dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
		val call = backend.calls.single()
		controller.pause().stop()
		idle()
		assertFalse(dialog.isShowing)
		assertTrue(call.handle.cancelled)
		call.listener.onSuccess()
		assertEquals(0, deleted)
	}

	@Test
	fun menuConfigurationCanRemoveDestructiveAction() {
		General.getSharedPrefs(app).edit().putStringSet(app.getString(
			R.string.pref_menus_multireddits_context_items_key), emptySet()).apply()
		assertNull(MultiredditActionMenus.showMultireddit(activity, account, target, null))
	}

	@Test
	fun removedAccountCannotSubmitCapturedConfirmation() {
		val backend = FakeBackend()
		val dialog = show(backend)
		ReflectionHelpers.setField(RedditAccountManager.getInstance(app),
			"accountsCache", arrayListOf<RedditAccount>())
		dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
		assertTrue(backend.calls.isEmpty())
	}
}

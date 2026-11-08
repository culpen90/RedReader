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

package org.quantumbadger.redreader.test.audio;

import android.content.Intent;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.view.View;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.quantumbadger.redreader.activities.HtmlViewActivity;
import org.quantumbadger.redreader.adapters.FilteredCommentListingManager;
import org.quantumbadger.redreader.audio.NativeTTSManager;
import org.quantumbadger.redreader.common.General;
import org.quantumbadger.redreader.common.PrefsUtility;
import org.quantumbadger.redreader.fragments.CommentListingFragment;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicReference;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, shadows = {NativeTTSManagerTest.TestTTS.class})
@LooperMode(LooperMode.Mode.PAUSED)
public class CommentReadAloudLifecycleTest {

	private ActivityController<HtmlViewActivity> mHost;
	private CommentListingFragment mFragment;
	private NativeTTSManager mManager;

	private static void setStatic(final Class<?> type, final String name, final Object value)
			throws Exception {
		final Field field = type.getDeclaredField(name);
		field.setAccessible(true);
		field.set(null, value);
	}

	@Before
	public void setUp() throws Exception {
		NativeTTSManagerTest.TestTTS.reset();
		setStatic(NativeTTSManager.class, "sInstance", null);
		setStatic(org.quantumbadger.redreader.account.RedditAccountManager.class,
				"singleton", null);
		final Field cachedPrefs = General.class.getDeclaredField("mPrefs");
		cachedPrefs.setAccessible(true);
		((AtomicReference<?>)cachedPrefs.get(null)).set(null);
		final android.app.Application app = RuntimeEnvironment.getApplication();
		setStatic(PrefsUtility.class, "mRes", app.getResources());
		setStatic(PrefsUtility.class, "sharedPrefs", General.getSharedPrefs(app));
		setStatic(PrefsUtility.class, "appContext", app);
		final Intent intent = new Intent(app, HtmlViewActivity.class);
		intent.putExtra("html", "<p>Test</p>");
		intent.putExtra("title", "Test");
		mHost = Robolectric.buildActivity(HtmlViewActivity.class, intent).setup().visible();
		mFragment = new CommentListingFragment(
				mHost.get(), null, new ArrayList<>(), null, null, false);
		mHost.get().setContentView(mFragment.createCombinedListingAndOverlayView());
		mManager = NativeTTSManager.getInstance(app);
	}

	@After
	public void tearDown() {
		mManager.stop();
		mHost.pause().stop().destroy();
	}

	private void start(final CommentListingFragment fragment) throws Exception {
		final NativeTTSManager.Listener listener = new NativeTTSManager.Listener() {
			@Override
			public void onTTSStateChanged(final boolean speaking) {}

			@Override
			public void onUtteranceStarted(final int position) {}

			@Override
			public void onTTSError() {}
		};
		final Field field = CommentListingFragment.class.getDeclaredField("mTTSListener");
		field.setAccessible(true);
		field.set(fragment, listener);
		mManager.setListener(listener);
		mManager.readAloud(Collections.singletonList(new NativeTTSManager.TTSItem("Test", 0)));
		final Field initialized = NativeTTSManager.class.getDeclaredField("mIsInitialized");
		initialized.setAccessible(true);
		if(!initialized.getBoolean(mManager)) {
			NativeTTSManagerTest.TestTTS.init(TextToSpeech.SUCCESS);
		}
		Assert.assertTrue(mManager.isSpeaking());
	}

	@Test
	public void testStoppingHostStopsSpeechWhileViewRemainsAttached() throws Exception {
		start(mFragment);
		final View listing = mFragment.getListingView();
		Assert.assertTrue(listing.isAttachedToWindow());
		mHost.pause().stop();
		Assert.assertTrue(listing.isAttachedToWindow());
		Assert.assertFalse(mManager.isSpeaking());
		Assert.assertEquals(1, NativeTTSManagerTest.TestTTS.sShutdowns);
		mHost.start().resume();
	}

	@Test
	public void testRemovingListingStopsSpeech() throws Exception {
		start(mFragment);
		mHost.get().setContentView(new View(mHost.get()));
		Shadows.shadowOf(Looper.getMainLooper()).idle();
		Assert.assertFalse(mManager.isSpeaking());
		Assert.assertEquals(1, NativeTTSManagerTest.TestTTS.sShutdowns);
	}

	@Test
	public void testOldListingCannotStopNewListingPlayback() throws Exception {
		start(mFragment);
		final CommentListingFragment replacement = new CommentListingFragment(
				mHost.get(), null, new ArrayList<>(), null, null, false);
		start(replacement);
		mHost.get().setContentView(replacement.createCombinedListingAndOverlayView());
		Shadows.shadowOf(Looper.getMainLooper()).idle();
		Assert.assertTrue(mManager.isSpeaking());
	}
	@Test
	public void testListingMutationStopsBeforeAdapterPositionsChange() throws Exception {
		start(mFragment);
		final Field field = CommentListingFragment.class.getDeclaredField("mCommentListingManager");
		field.setAccessible(true);
		final FilteredCommentListingManager manager
				= (FilteredCommentListingManager)field.get(mFragment);
		manager.addViewToItems(new View(mHost.get()));
		Assert.assertFalse(mManager.isSpeaking());
		Assert.assertEquals(1, NativeTTSManagerTest.TestTTS.sShutdowns);
	}

}

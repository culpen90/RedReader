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

package org.quantumbadger.redreader.test.general

import android.content.SharedPreferences
import android.view.View
import android.widget.ImageButton
import android.widget.RelativeLayout
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.SilenceMediaSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.quantumbadger.redreader.R
import org.quantumbadger.redreader.common.General
import org.quantumbadger.redreader.common.PrefsUtility
import org.quantumbadger.redreader.common.SharedPrefsWrapper
import org.quantumbadger.redreader.views.video.ExoPlayerWrapperView
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class VideoAutoplayPreferenceTest {

	private val app = RuntimeEnvironment.getApplication()
	private lateinit var prefs: SharedPreferences

	@Before
	fun setUpPreferences() {
		app.setTheme(R.style.RR_Dark)
		ReflectionHelpers.getStaticField<AtomicReference<SharedPrefsWrapper>>(
			General::class.java, "mPrefs"
		).set(null)
		val wrapped = General.getSharedPrefs(app)
		prefs = ReflectionHelpers.getField(wrapped, "mPrefs")
		prefs.edit().clear().commit()
		ReflectionHelpers.setStaticField(PrefsUtility::class.java, "sharedPrefs", wrapped)
		ReflectionHelpers.setStaticField(PrefsUtility::class.java, "mRes", app.resources)
		ReflectionHelpers.setStaticField(PrefsUtility::class.java, "appContext", app)
	}

	private fun withPlayer(check: (ExoPlayerWrapperView, ExoPlayer) -> Unit) {
		val source = SilenceMediaSource.Factory().setDurationUs(60_000_000).createMediaSource()
		val view = ExoPlayerWrapperView(app, source, {}, 0)
		try {
			check(view, ReflectionHelpers.getField(view, "mVideoPlayer"))
		} finally {
			view.release()
		}
	}

	@Test
	fun videosStillAutoplayByDefault() {
		withPlayer { _, player -> assertTrue(player.playWhenReady) }
	}

	@Test
	fun pausedVideoOffersAVisiblePlayButtonImmediately() {
		prefs.edit()
			.putBoolean(app.getString(R.string.pref_behaviour_video_autoplay_key), false)
			.putBoolean(app.getString(R.string.pref_behaviour_video_playback_controls_key), true)
			.commit()
		withPlayer { view, player ->
			assertFalse(player.playWhenReady)
			val controls: RelativeLayout = ReflectionHelpers.getField(view, "mControlView")
			val button: ImageButton = ReflectionHelpers.getField(view, "mPlayButton")
			assertEquals(View.VISIBLE, controls.visibility)
			assertEquals(app.getString(R.string.video_play), button.contentDescription)
			button.performClick()
			assertTrue(player.playWhenReady)
		}
	}

	@Test
	fun controlsDisabledStillAllowsPlayback() {
		prefs.edit()
			.putBoolean(app.getString(R.string.pref_behaviour_video_autoplay_key), false)
			.putBoolean(app.getString(R.string.pref_behaviour_video_playback_controls_key), false)
			.commit()
		withPlayer { _, player -> assertTrue(player.playWhenReady) }
	}
}

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

import android.content.Context
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
import org.quantumbadger.redreader.common.PrefsUtility
import org.quantumbadger.redreader.views.video.ExoPlayerWrapperView
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class VideoLoopPreferenceTest {

	private val app = RuntimeEnvironment.getApplication()
	private lateinit var prefs: SharedPreferences

	@Before
	fun setUpPreferences() {
		app.setTheme(R.style.RR_Dark)
		prefs = app.getSharedPreferences("video-policy-test", Context.MODE_PRIVATE)
		prefs.edit().clear().commit()
		ReflectionHelpers.setStaticField(PrefsUtility::class.java, "sharedPrefs", prefs)
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
	fun loopingRemainsEnabledByDefault() {
		withPlayer { _, player -> assertEquals(Player.REPEAT_MODE_ONE, player.repeatMode) }
	}

	@Test
	fun restoredDisabledPreferenceIsHonored() {
		prefs.edit().putBoolean(app.getString(R.string.pref_behaviour_video_loop_key), false).commit()
		withPlayer { _, player -> assertEquals(Player.REPEAT_MODE_OFF, player.repeatMode) }
	}
}

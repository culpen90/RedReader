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
import android.view.MotionEvent
import android.view.View
import androidx.media3.exoplayer.source.SilenceMediaSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.quantumbadger.redreader.R
import org.quantumbadger.redreader.common.General
import org.quantumbadger.redreader.common.PrefsUtility
import org.quantumbadger.redreader.common.SharedPrefsWrapper
import org.quantumbadger.redreader.views.imageview.BasicGestureHandler
import org.quantumbadger.redreader.views.video.ExoPlayerWrapperView
import org.quantumbadger.redreader.views.video.VideoGestureHandler
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class GestureVideoCancellationTest {

	private val app get() = RuntimeEnvironment.getApplication()

	@Before
	fun setUpPreferences() {
		app.setTheme(R.style.RR_Dark)
		ReflectionHelpers.getStaticField<AtomicReference<SharedPrefsWrapper>>(
			General::class.java, "mPrefs"
		).set(null)
		val wrapped = General.getSharedPrefs(app)
		val prefs = ReflectionHelpers.getField<SharedPreferences>(wrapped, "mPrefs")
		prefs.edit().clear()
			.putBoolean(app.getString(R.string.pref_behaviour_video_playback_controls_key), true)
			.commit()
		ReflectionHelpers.setStaticField(PrefsUtility::class.java, "sharedPrefs", wrapped)
		ReflectionHelpers.setStaticField(PrefsUtility::class.java, "mRes", app.resources)
		ReflectionHelpers.setStaticField(PrefsUtility::class.java, "appContext", app)
	}

	private class TapListener(private val view: ExoPlayerWrapperView)
			: BasicGestureHandler.Listener {
		var taps = 0
		var swipeEnds = 0
		override fun onSingleTap() {
			taps++
			view.handleTap()
		}
		override fun onHorizontalSwipe(pixels: Float) { }
		override fun onHorizontalSwipeEnd() { swipeEnds++ }
	}

	private fun withPlayer(
		check: (ExoPlayerWrapperView, VideoGestureHandler, TapListener) -> Unit
	) {
		val source = SilenceMediaSource.Factory().setDurationUs(60_000_000).createMediaSource()
		val view = ExoPlayerWrapperView(app, source, {}, 0)
		try {
			val listener = TapListener(view)
			check(view, VideoGestureHandler(listener, view), listener)
		} finally {
			view.release()
		}
	}

	private fun touch(
		handler: VideoGestureHandler,
		downTime: Long,
		time: Long,
		action: Int,
		ids: IntArray = intArrayOf(3)
	) {
		val properties = ids.map { pointerId ->
			MotionEvent.PointerProperties().apply {
				id = pointerId
				toolType = MotionEvent.TOOL_TYPE_FINGER
			}
		}.toTypedArray()
		val coordinates = ids.map { pointerId ->
			MotionEvent.PointerCoords().apply {
				x = pointerId * 10f
				y = 10f
				pressure = 1f
				size = 1f
			}
		}.toTypedArray()
		val event = MotionEvent.obtain(
			downTime, time, action, ids.size, properties, coordinates,
			0, 0, 1f, 1f, 0, 0, 0, 0
		)
		try {
			assertTrue(handler.onTouch(null, event))
		} finally {
			event.recycle()
		}
	}

	@Test
	fun cancelledVideoGestureDoesNotToggleActualPlayerControls() {
		withPlayer { view, handler, listener ->
			val initialVisibility = view.isControlViewVisible()
			touch(handler, 0, 0, MotionEvent.ACTION_DOWN)
			touch(handler, 0, 20, MotionEvent.ACTION_CANCEL)
			assertEquals(0, listener.taps)
			assertEquals(1, listener.swipeEnds)
			assertEquals(initialVisibility, view.isControlViewVisible())
		}
	}

	@Test
	fun ordinaryVideoTapStillTogglesActualPlayerControls() {
		withPlayer { view, handler, listener ->
			val initialVisibility = view.isControlViewVisible()
			touch(handler, 0, 0, MotionEvent.ACTION_DOWN)
			touch(handler, 0, 20, MotionEvent.ACTION_UP)
			assertEquals(1, listener.taps)
			assertEquals(1, listener.swipeEnds)
			assertEquals(
				if(initialVisibility == View.VISIBLE) View.GONE else View.VISIBLE,
				view.isControlViewVisible()
			)
		}
	}

	@Test
	fun normalVideoTapWorksAfterCancelledSinglePointerGesture() {
		withPlayer { _, handler, listener ->
			touch(handler, 0, 0, MotionEvent.ACTION_DOWN)
			touch(handler, 0, 20, MotionEvent.ACTION_CANCEL)
			touch(handler, 30, 30, MotionEvent.ACTION_DOWN)
			touch(handler, 30, 40, MotionEvent.ACTION_UP)
			assertEquals(1, listener.taps)
			assertEquals(2, listener.swipeEnds)
		}
	}

	@Test
	fun cancellingPinchAndSparePointerLeavesNextVideoTapUsable() {
		withPlayer { view, handler, listener ->
			val initialVisibility = view.isControlViewVisible()
			touch(handler, 0, 0, MotionEvent.ACTION_DOWN, intArrayOf(3))
			touch(handler, 0, 10, MotionEvent.ACTION_POINTER_DOWN or
				(1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), intArrayOf(3, 7))
			touch(handler, 0, 15, MotionEvent.ACTION_POINTER_DOWN or
				(2 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), intArrayOf(3, 7, 9))
			touch(handler, 0, 20, MotionEvent.ACTION_CANCEL, intArrayOf(3, 7, 9))
			assertEquals(0, listener.taps)
			assertEquals(initialVisibility, view.isControlViewVisible())
			touch(handler, 30, 30, MotionEvent.ACTION_DOWN)
			touch(handler, 30, 40, MotionEvent.ACTION_UP)
			assertEquals(1, listener.taps)
		}
	}
}

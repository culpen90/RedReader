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

import android.view.MotionEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.quantumbadger.redreader.views.imageview.BasicGestureHandler
import org.quantumbadger.redreader.views.imageview.FingerTracker
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class GestureCancellationTest {

	private class TapListener : BasicGestureHandler.Listener {
		var taps = 0
		var swipeEnds = 0
		override fun onSingleTap() { taps++ }
		override fun onHorizontalSwipe(pixels: Float) { }
		override fun onHorizontalSwipeEnd() { swipeEnds++ }
	}

	private fun touch(handler: BasicGestureHandler, action: Int, time: Long) {
		val event = MotionEvent.obtain(0, time, action, 10f, 10f, 0)
		try {
			handler.onTouch(null, event)
		} finally {
			event.recycle()
		}
	}

	@Test
	fun cancelledGestureDoesNotTapAndStillEndsSwipe() {
		val listener = TapListener()
		val handler = BasicGestureHandler(listener)
		touch(handler, MotionEvent.ACTION_DOWN, 0)
		touch(handler, MotionEvent.ACTION_MOVE, 10)
		touch(handler, MotionEvent.ACTION_CANCEL, 20)
		assertEquals(0, listener.taps)
		assertEquals(1, listener.swipeEnds)
	}

	@Test
	fun ordinaryTapStillWorks() {
		val listener = TapListener()
		val handler = BasicGestureHandler(listener)
		touch(handler, MotionEvent.ACTION_DOWN, 0)
		touch(handler, MotionEvent.ACTION_UP, 20)
		assertEquals(1, listener.taps)
		assertEquals(1, listener.swipeEnds)
	}

	@Test
	fun tapAfterCancellationStartsWithCleanState() {
		val listener = TapListener()
		val handler = BasicGestureHandler(listener)
		touch(handler, MotionEvent.ACTION_DOWN, 0)
		touch(handler, MotionEvent.ACTION_CANCEL, 20)
		touch(handler, MotionEvent.ACTION_DOWN, 30)
		touch(handler, MotionEvent.ACTION_UP, 40)
		assertEquals(1, listener.taps)
		assertEquals(2, listener.swipeEnds)
	}

	private class FingerListener : FingerTracker.FingerListener {
		val releases = mutableListOf<Boolean>()
		var downs = 0
		override fun onFingerDown(finger: FingerTracker.Finger) { downs++ }
		override fun onFingersMoved() { }
		override fun onFingerUp(finger: FingerTracker.Finger, cancelled: Boolean) {
			releases.add(cancelled)
		}
	}

	private fun track(tracker: FingerTracker, action: Int, ids: IntArray) {
		val properties = ids.map { id ->
			MotionEvent.PointerProperties().apply {
				this.id = id
				toolType = MotionEvent.TOOL_TYPE_FINGER
			}
		}.toTypedArray()
		val coordinates = ids.map { id ->
			MotionEvent.PointerCoords().apply { x = id * 10f; y = 10f }
		}.toTypedArray()
		val event = MotionEvent.obtain(
			0, 20, action, ids.size, properties, coordinates,
			0, 0, 1f, 1f, 0, 0, 0, 0
		)
		try {
			tracker.onTouchEvent(event)
		} finally {
			event.recycle()
		}
	}

	@Test
	fun cancellationReleasesEveryPointerAndAllowsANewGesture() {
		val listener = FingerListener()
		val tracker = FingerTracker(listener)
		track(tracker, MotionEvent.ACTION_DOWN, intArrayOf(3))
		track(tracker, MotionEvent.ACTION_POINTER_DOWN or (1 shl 8), intArrayOf(3, 7))
		track(tracker, MotionEvent.ACTION_CANCEL, intArrayOf(3, 7))
		assertEquals(listOf(true, true), listener.releases)
		track(tracker, MotionEvent.ACTION_DOWN, intArrayOf(9))
		track(tracker, MotionEvent.ACTION_UP, intArrayOf(9))
		assertEquals(listOf(true, true, false), listener.releases)
		assertEquals(3, listener.downs)
	}

	@Test
	fun ordinaryPointerReleasesAreNotCancelled() {
		val listener = FingerListener()
		val tracker = FingerTracker(listener)
		track(tracker, MotionEvent.ACTION_DOWN, intArrayOf(3))
		track(tracker, MotionEvent.ACTION_POINTER_DOWN or (1 shl 8), intArrayOf(3, 7))
		track(tracker, MotionEvent.ACTION_POINTER_UP or (1 shl 8), intArrayOf(3, 7))
		track(tracker, MotionEvent.ACTION_UP, intArrayOf(3))
		assertEquals(listOf(false, false), listener.releases)
	}

	@Test
	fun duplicateCancelDoesNotReleaseInactivePointers() {
		val listener = FingerListener()
		val tracker = FingerTracker(listener)
		track(tracker, MotionEvent.ACTION_DOWN, intArrayOf(3))
		track(tracker, MotionEvent.ACTION_CANCEL, intArrayOf(3))
		track(tracker, MotionEvent.ACTION_CANCEL, intArrayOf(3))
		assertTrue(listener.releases.single())
	}
}

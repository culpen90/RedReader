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

package org.quantumbadger.redreader.views

import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.quantumbadger.redreader.R
import org.quantumbadger.redreader.common.General
import org.quantumbadger.redreader.common.PrefsUtility
import org.quantumbadger.redreader.common.SharedPrefsWrapper
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [28])
class IndentColorPreferenceTest {

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
	}

	private fun lineColors(allLines: Boolean, colorful: Boolean?): List<Int> {
		prefs.edit().putBoolean(app.getString(R.string.pref_appearance_indentlines_key), allLines)
			.apply {
				if (colorful != null) {
					putBoolean(app.getString(R.string.pref_appearance_colorful_indentlines_key), colorful)
				}
			}.commit()
		val view = IndentView(app)
		view.layoutParams = ViewGroup.LayoutParams(1, 30)
		view.setIndentation(3)
		val width = General.dpToPixels(app, 30f)
		view.measure(
			View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
			View.MeasureSpec.makeMeasureSpec(30, View.MeasureSpec.EXACTLY)
		)
		view.layout(0, 0, width, 30)
		val bitmap = Bitmap.createBitmap(width, 30, Bitmap.Config.ARGB_8888)
		view.draw(Canvas(bitmap))
		return (1..3).map { bitmap.getPixel(General.dpToPixels(app, it * 10f) - 1, 15) }
	}

	@Test
	fun existingAllLinePreferenceKeepsMonochromeGuidesByDefault() {
		assertFalse(PrefsUtility.pref_appearance_colorful_indentlines())
		val colors = lineColors(allLines = true, colorful = null)
		assertEquals(1, colors.distinct().size)
	}

	@Test
	fun optInColorsEachAncestorWithoutRemovingGuides() {
		val colors = lineColors(allLines = true, colorful = true)
		assertEquals(3, colors.distinct().size)
		assertTrue(PrefsUtility.isRefreshRequired(
			app, app.getString(R.string.pref_appearance_colorful_indentlines_key)
		))
	}

	@Test
	fun optInPreservesTheSingleLineStyle() {
		val colors = lineColors(allLines = false, colorful = true)
		assertEquals(colors[0], colors[1])
		assertNotEquals(colors[1], colors[2])
	}
}
